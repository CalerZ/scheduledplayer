package com.caleb.scheduledplayer.service.player;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.caleb.scheduledplayer.ScheduledPlayerApp;
import com.caleb.scheduledplayer.R;
import com.caleb.scheduledplayer.data.converter.Converters;
import com.caleb.scheduledplayer.data.database.AppDatabase;
import com.caleb.scheduledplayer.data.entity.TaskEntity;
import com.caleb.scheduledplayer.data.repository.TaskLogRepository;
import com.caleb.scheduledplayer.presentation.ui.main.MainActivity;
import com.caleb.scheduledplayer.util.AudioFileValidator;
import com.caleb.scheduledplayer.util.BluetoothHelper;
import com.caleb.scheduledplayer.util.LogErrorType;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.Permission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 音频播放前台服务
 * 支持多任务同时播放（混音）
 */
public class AudioPlaybackService extends Service {

    private static final String TAG = "AudioPlaybackService";
    private static final int NOTIFICATION_ID = 1;

    // Intent Actions
    public static final String ACTION_START_TASK = "com.caleb.scheduledplayer.START_TASK";
    public static final String ACTION_STOP_TASK = "com.caleb.scheduledplayer.STOP_TASK";
    public static final String ACTION_STOP_ALL = "com.caleb.scheduledplayer.STOP_ALL";
    public static final String EXTRA_TASK_ID = "task_id";

    private final IBinder binder = new LocalBinder();
    private final Map<Long, TaskPlayer> taskPlayers = new HashMap<>();
    private final Map<Long, Long> taskLogIds = new HashMap<>();  // 任务ID -> 日志ID映射
    private final Map<Long, Integer> taskOutputDevices = new HashMap<>();  // 任务ID -> 输出设备映射
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService executorService;
    private PowerManager.WakeLock wakeLock;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private boolean hasAudioFocus = false;
    private TaskLogRepository logRepository;
    private BluetoothHelper bluetoothHelper;

    private PlaybackCallback playbackCallback;

    public class LocalBinder extends Binder {
        public AudioPlaybackService getService() {
            return AudioPlaybackService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate: Service created, pid=" + android.os.Process.myPid());
        executorService = Executors.newCachedThreadPool();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        logRepository = new TaskLogRepository(getApplication());
        bluetoothHelper = new BluetoothHelper(this);
        acquireWakeLock();
        
        // 开始监听蓝牙连接状态
        bluetoothHelper.startListening(new BluetoothHelper.BluetoothConnectionListener() {
            @Override
            public void onBluetoothAudioConnected() {
                Log.d(TAG, "Bluetooth audio connected");
            }

            @Override
            public void onBluetoothAudioDisconnected() {
                Log.d(TAG, "Bluetooth audio disconnected");
                // 停止所有需要蓝牙的任务
                mainHandler.post(() -> stopBluetoothOnlyTasks());
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand: flags=" + flags + ", startId=" + startId + 
              ", action=" + (intent != null ? intent.getAction() : "null"));
        // 立即启动前台服务，避免 ANR
        startForeground(NOTIFICATION_ID, createNotification());
        
        if (intent != null) {
            String action = intent.getAction();
            if (action != null) {
                switch (action) {
                    case ACTION_START_TASK:
                        long taskId = intent.getLongExtra(EXTRA_TASK_ID, -1);
                        if (taskId != -1) {
                            startTask(taskId);
                        }
                        break;
                    case ACTION_STOP_TASK:
                        long stopTaskId = intent.getLongExtra(EXTRA_TASK_ID, -1);
                        if (stopTaskId != -1) {
                            stopTask(stopTaskId);
                        }
                        break;
                    case ACTION_STOP_ALL:
                        stopAllTasks();
                        break;
                }
            }
        }

        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy called, taskPlayers count: " + taskPlayers.size());
        stopAllTasks();
        releaseWakeLock();
        abandonAudioFocus();
        if (bluetoothHelper != null) {
            bluetoothHelper.stopListening();
        }
        if (executorService != null) {
            executorService.shutdown();
        }
    }
    
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "onTaskRemoved: app removed from recent tasks");
        // 当应用从最近任务列表移除时，重新启动服务以保持播放
        if (!taskPlayers.isEmpty()) {
            Log.d(TAG, "Restarting service to continue playback");
            Intent restartIntent = new Intent(this, AudioPlaybackService.class);
            restartIntent.setAction(ACTION_START_TASK);
            // 重新启动第一个正在播放的任务
            for (Long taskId : taskPlayers.keySet()) {
                restartIntent.putExtra(EXTRA_TASK_ID, taskId);
                break;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent);
            } else {
                startService(restartIntent);
            }
        }
        super.onTaskRemoved(rootIntent);
    }

    /**
     * 启动任务播放
     */
    public void startTask(long taskId) {
        executorService.execute(() -> {
            TaskEntity task = AppDatabase.getInstance(this).taskDao().getTaskByIdSync(taskId);
            if (task != null) {
                mainHandler.post(() -> startTaskPlayback(task));
            }
        });
    }

    /**
     * 停止任务播放
     */
    public void stopTask(long taskId) {
        TaskPlayer player = taskPlayers.remove(taskId);
        taskOutputDevices.remove(taskId);
        if (player != null) {
            // 更新日志为成功状态
            Long logId = taskLogIds.remove(taskId);
            if (logId != null) {
                executorService.execute(() -> {
                    logRepository.updateLogSuccess(logId, player.getPlayedFiles());
                });
            }
            player.stop();
        }
        updateNotificationOrStop();
    }

    /**
     * 停止所有任务
     */
    public void stopAllTasks() {
        for (Map.Entry<Long, TaskPlayer> entry : taskPlayers.entrySet()) {
            long taskId = entry.getKey();
            TaskPlayer player = entry.getValue();
            
            // 更新日志为成功状态
            Long logId = taskLogIds.remove(taskId);
            if (logId != null) {
                final List<String> playedFiles = player.getPlayedFiles();
                executorService.execute(() -> {
                    logRepository.updateLogSuccess(logId, playedFiles);
                });
            }
            player.stop();
        }
        taskPlayers.clear();
        taskOutputDevices.clear();
        stopForeground(true);
        stopSelf();
    }

    /**
     * 检查任务是否正在播放
     */
    public boolean isTaskPlaying(long taskId) {
        return taskPlayers.containsKey(taskId);
    }

    /**
     * 获取正在播放的任务数量
     */
    public int getPlayingTaskCount() {
        return taskPlayers.size();
    }

    /**
     * 设置播放回调
     */
    public void setPlaybackCallback(PlaybackCallback callback) {
        this.playbackCallback = callback;
    }

    private void startTaskPlayback(TaskEntity task) {
        // 检查蓝牙播放模式
        if (task.getOutputDevice() == TaskEntity.OUTPUT_DEVICE_BLUETOOTH) {
            if (!bluetoothHelper.isBluetoothAudioConnected()) {
                Log.w(TAG, "Task " + task.getId() + " requires bluetooth but no bluetooth audio connected");
                // 记录失败日志
                executorService.execute(() -> {
                    long logId = logRepository.createLog(task.getId());
                    logRepository.updateLogFailed(logId, LogErrorType.BLUETOOTH_NOT_CONNECTED, "蓝牙音频设备未连接");
                });
                return;
            }
        }
        
        // 如果任务已在播放，检查音频列表是否变化
        if (taskPlayers.containsKey(task.getId())) {
            TaskPlayer existingPlayer = taskPlayers.get(task.getId());
            List<String> newAudioPaths = Converters.parseAudioPaths(task.getAudioPaths());
            
            // 如果音频列表没变，继续播放，不重启
            if (existingPlayer != null && existingPlayer.isSamePlaylist(newAudioPaths)) {
                Log.d(TAG, "Task " + task.getId() + " is already playing with same playlist, skipping restart");
                return;
            }
            
            // 音频列表变了，停止当前播放（但不触发服务停止）
            TaskPlayer player = taskPlayers.remove(task.getId());
            if (player != null) {
                player.stopWithoutCallback();
            }
        }

        // 请求音频焦点
        if (!hasAudioFocus) {
            requestAudioFocus();
        }

        // 解析音频路径
        List<String> audioPaths = Converters.parseAudioPaths(task.getAudioPaths());
        if (audioPaths.isEmpty()) {
            Log.w(TAG, "Task " + task.getId() + " has no audio files");
            // 记录失败日志
            executorService.execute(() -> {
                long logId = logRepository.createLog(task.getId());
                logRepository.updateLogFailed(logId, LogErrorType.FILE_MISSING, "没有可播放的音频文件");
            });
            return;
        }

        // 创建执行日志
        executorService.execute(() -> {
            long logId = logRepository.createLog(task.getId());
            mainHandler.post(() -> {
                taskLogIds.put(task.getId(), logId);
            });
        });

        // 记录任务的输出设备设置
        taskOutputDevices.put(task.getId(), task.getOutputDevice());

        // 创建任务播放器
        TaskPlayer player = new TaskPlayer(task, audioPaths);
        taskPlayers.put(task.getId(), player);
        player.start();

        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification());

        if (playbackCallback != null) {
            playbackCallback.onTaskStarted(task.getId());
            // 延迟通知状态变化，等待 MediaPlayer 准备完成
            mainHandler.postDelayed(() -> notifyPlaybackStateChanged(), 500);
        }
    }
    
    /**
     * 停止所有仅蓝牙播放的任务（蓝牙断开时调用）
     */
    private void stopBluetoothOnlyTasks() {
        List<Long> tasksToStop = new ArrayList<>();
        
        for (Map.Entry<Long, Integer> entry : taskOutputDevices.entrySet()) {
            if (entry.getValue() == TaskEntity.OUTPUT_DEVICE_BLUETOOTH) {
                tasksToStop.add(entry.getKey());
            }
        }
        
        for (Long taskId : tasksToStop) {
            Log.d(TAG, "Stopping bluetooth-only task " + taskId + " due to bluetooth disconnection");
            
            // 记录日志
            Long logId = taskLogIds.get(taskId);
            if (logId != null) {
                executorService.execute(() -> {
                    logRepository.updateLogFailed(logId, LogErrorType.BLUETOOTH_DISCONNECTED, "蓝牙音频设备断开连接");
                });
            }
            
            stopTask(taskId);
        }
    }

    private void updateNotificationOrStop() {
        if (taskPlayers.isEmpty()) {
            stopForeground(true);
            stopSelf();
        } else {
            // 更新通知
            Notification notification = createNotification();
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // 停止所有任务的 PendingIntent
        Intent stopIntent = new Intent(this, AudioPlaybackService.class);
        stopIntent.setAction(ACTION_STOP_ALL);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String contentText = taskPlayers.size() == 1 
                ? "正在播放 1 个任务" 
                : "正在播放 " + taskPlayers.size() + " 个任务";

        return new NotificationCompat.Builder(this, ScheduledPlayerApp.NOTIFICATION_CHANNEL_PLAYBACK)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_media_pause, "停止", stopPendingIntent)
                .setOngoing(true)
                .build();
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "ScheduledPlayer:PlaybackWakeLock"
            );
            wakeLock.acquire(10 * 60 * 60 * 1000L); // 10 小时
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
    }

    private void requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();

            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(audioAttributes)
                    .setOnAudioFocusChangeListener(focusChange -> {
                        // 可以根据需要处理音频焦点变化
                        // 这里选择忽略，继续播放
                    })
                    .build();

            int result = audioManager.requestAudioFocus(audioFocusRequest);
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        } else {
            int result = audioManager.requestAudioFocus(
                    focusChange -> {},
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
            );
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        }
    }

    private void abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            audioManager.abandonAudioFocus(null);
        }
        hasAudioFocus = false;
    }

    /**
     * 任务播放器内部类
     * 管理单个任务的音频播放
     */
    private class TaskPlayer implements MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener {
        private final TaskEntity task;
        private final List<String> playlist;
        private final List<String> playedFiles = new ArrayList<>();  // 已播放的文件列表
        private MediaPlayer mediaPlayer;
        private int currentIndex = 0;
        private boolean isPlaying = false;
        private boolean isPaused = false;
        
        // 随机暂停相关
        private final Handler pauseHandler = new Handler(Looper.getMainLooper());
        private Runnable pauseRunnable;
        private boolean isRandomPausing = false;
        private long pauseEndTime = 0;

        TaskPlayer(TaskEntity task, List<String> audioPaths) {
            this.task = task;
            this.playlist = new ArrayList<>(audioPaths);

            // 如果是随机模式，打乱播放列表
            if (task.getPlayMode() == TaskEntity.PLAY_MODE_RANDOM) {
                Collections.shuffle(this.playlist);
            }
        }

        void start() {
            isPlaying = true;
            isPaused = false;
            isRandomPausing = false;
            playCurrentTrack();
        }

        void stop() {
            isPlaying = false;
            isPaused = false;
            // 清理随机暂停定时器
            if (pauseRunnable != null) {
                pauseHandler.removeCallbacks(pauseRunnable);
            }
            isRandomPausing = false;
            pauseEndTime = 0;
            releaseMediaPlayer();
            if (playbackCallback != null) {
                playbackCallback.onTaskStopped(task.getId());
            }
        }
        
        void pause() {
            if (mediaPlayer != null && isActuallyPlaying()) {
                mediaPlayer.pause();
                isPaused = true;
            }
        }
        
        void resume() {
            if (mediaPlayer != null && isPaused) {
                mediaPlayer.start();
                isPaused = false;
            }
        }
        
        /**
         * 跳过随机暂停，立即播放下一首
         */
        void skipPause() {
            if (isRandomPausing) {
                Log.d(TAG, "Skipping random pause, playing next track");
                pauseHandler.removeCallbacks(pauseRunnable);
                isRandomPausing = false;
                pauseEndTime = 0;
                currentIndex++;
                playCurrentTrack();
            }
        }
        
        /**
         * 获取剩余暂停时间（毫秒）
         */
        long getRemainingPauseMillis() {
            if (!isRandomPausing || pauseEndTime == 0) return 0;
            return Math.max(0, pauseEndTime - System.currentTimeMillis());
        }
        
        boolean isActuallyPlaying() {
            try {
                return mediaPlayer != null && mediaPlayer.isPlaying();
            } catch (IllegalStateException e) {
                return false;
            }
        }
        
        String getCurrentSongName() {
            if (currentIndex >= 0 && currentIndex < playlist.size()) {
                return AudioFileValidator.getFileName(AudioPlaybackService.this, playlist.get(currentIndex));
            }
            return null;
        }
        
        int getCurrentPosition() {
            try {
                return mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0;
            } catch (IllegalStateException e) {
                return 0;
            }
        }
        
        int getDuration() {
            try {
                return mediaPlayer != null ? mediaPlayer.getDuration() : 0;
            } catch (IllegalStateException e) {
                return 0;
            }
        }

        /**
         * 停止播放但不触发回调（用于重启任务时）
         */
        void stopWithoutCallback() {
            isPlaying = false;
            isPaused = false;
            // 清理随机暂停定时器
            if (pauseRunnable != null) {
                pauseHandler.removeCallbacks(pauseRunnable);
            }
            isRandomPausing = false;
            pauseEndTime = 0;
            releaseMediaPlayer();
        }

        /**
         * 检查播放列表是否相同
         */
        boolean isSamePlaylist(List<String> otherPaths) {
            if (otherPaths == null || otherPaths.size() != playlist.size()) {
                return false;
            }
            // 比较内容（忽略顺序，因为可能是随机模式）
            return playlist.containsAll(otherPaths) && otherPaths.containsAll(playlist);
        }

        /**
         * 获取已播放的文件列表
         */
        List<String> getPlayedFiles() {
            return new ArrayList<>(playedFiles);
        }

        private void playCurrentTrack() {
            Log.d(TAG, "playCurrentTrack: isPlaying=" + isPlaying + ", playlistSize=" + playlist.size() + ", currentIndex=" + currentIndex);
            if (!isPlaying || playlist.isEmpty()) {
                Log.w(TAG, "playCurrentTrack: skipping because isPlaying=" + isPlaying + " or playlist is empty");
                return;
            }

            if (currentIndex >= playlist.size()) {
                // 播放列表结束，循环播放（所有模式都循环，直到任务结束时间）
                currentIndex = 0;
                // 随机模式下，重新洗牌
                if (task.getPlayMode() == TaskEntity.PLAY_MODE_RANDOM) {
                    Collections.shuffle(playlist);
                }
            }

            String audioPath = playlist.get(currentIndex);
            Log.d(TAG, "playCurrentTrack: playing " + audioPath);
            releaseMediaPlayer();

            try {
                mediaPlayer = new MediaPlayer();
                mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build());

                // 设置音量
                float volume = task.getVolume() / 100f;
                mediaPlayer.setVolume(volume, volume);

                // 设置数据源
                Uri uri = Uri.parse(audioPath);
                mediaPlayer.setDataSource(AudioPlaybackService.this, uri);
                mediaPlayer.setOnCompletionListener(this);
                mediaPlayer.setOnErrorListener(this);
                mediaPlayer.setOnInfoListener((mp, what, extra) -> {
                    Log.d(TAG, "MediaPlayer onInfo: what=" + what + ", extra=" + extra);
                    return false;
                });
                mediaPlayer.prepareAsync();
                mediaPlayer.setOnPreparedListener(mp -> {
                    Log.d(TAG, "MediaPlayer prepared, isPlaying=" + isPlaying);
                    if (isPlaying) {
                        try {
                            mp.start();
                            Log.d(TAG, "MediaPlayer started playing: " + audioPath + 
                                  ", duration=" + mp.getDuration() + "ms, isActuallyPlaying=" + mp.isPlaying());
                            // 记录已播放的文件
                            playedFiles.add(audioPath);
                            // 通知播放状态变化
                            notifyPlaybackStateChanged();
                        } catch (IllegalStateException e) {
                            Log.e(TAG, "Failed to start MediaPlayer", e);
                            currentIndex++;
                            playCurrentTrack();
                        }
                    }
                });

            } catch (SecurityException e) {
                Log.e(TAG, "Permission denied for audio: " + audioPath, e);
                // 记录权限错误到日志
                recordPlaybackError(task.getId(), LogErrorType.PERMISSION_DENIED, 
                        "权限被拒绝: " + getFileName(audioPath));
                // 跳到下一首
                currentIndex++;
                playCurrentTrack();
            } catch (IOException e) {
                Log.e(TAG, "Error playing audio: " + audioPath, e);
                // 记录文件缺失错误
                recordPlaybackError(task.getId(), LogErrorType.FILE_MISSING, 
                        "文件不存在: " + getFileName(audioPath));
                // 跳到下一首
                currentIndex++;
                playCurrentTrack();
            }
        }

        private void releaseMediaPlayer() {
            if (mediaPlayer != null) {
                try {
                    if (mediaPlayer.isPlaying()) {
                        mediaPlayer.stop();
                    }
                    mediaPlayer.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing MediaPlayer", e);
                }
                mediaPlayer = null;
            }
        }

        @Override
        public void onCompletion(MediaPlayer mp) {
            Log.d(TAG, "onCompletion: track finished, currentIndex=" + currentIndex);
            
            // 检查是否启用随机暂停
            if (task.isRandomPauseEnabled()) {
                startRandomPause();
            } else {
                currentIndex++;
                playCurrentTrack();
            }
        }
        
        /**
         * 开始随机暂停
         */
        private void startRandomPause() {
            int minMs = task.getMinPauseMinutes() * 60 * 1000;
            int maxMs = task.getMaxPauseMinutes() * 60 * 1000;
            int pauseDuration = minMs + new java.util.Random().nextInt(Math.max(1, maxMs - minMs + 1));
            
            Log.d(TAG, "Starting random pause for " + (pauseDuration / 1000) + " seconds");
            
            isRandomPausing = true;
            pauseEndTime = System.currentTimeMillis() + pauseDuration;
            
            // 释放当前 MediaPlayer
            releaseMediaPlayer();
            
            // 通知 UI 更新
            notifyPlaybackStateChanged();
            
            // 设置定时器
            pauseRunnable = () -> {
                Log.d(TAG, "Random pause ended, playing next track");
                isRandomPausing = false;
                pauseEndTime = 0;
                currentIndex++;
                playCurrentTrack();
            };
            pauseHandler.postDelayed(pauseRunnable, pauseDuration);
        }

        @Override
        public boolean onError(MediaPlayer mp, int what, int extra) {
            Log.e(TAG, "MediaPlayer error: what=" + what + ", extra=" + extra);
            // 记录播放器错误
            recordPlaybackError(task.getId(), LogErrorType.PLAYER_ERROR, 
                    "播放器错误: what=" + what + ", extra=" + extra);
            currentIndex++;
            playCurrentTrack();
            return true;
        }
    }

    /**
     * 记录播放错误到日志（仅记录，不更新状态为失败）
     */
    private void recordPlaybackError(long taskId, int errorType, String errorMessage) {
        Log.w(TAG, "Playback error for task " + taskId + ": " + errorMessage);
        // 这里只记录日志，不更新状态，因为可能还有其他文件可以播放
    }

    /**
     * 从路径中提取文件名
     */
    private String getFileName(String path) {
        if (path == null) return "unknown";
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < path.length() - 1) {
            return path.substring(lastSlash + 1);
        }
        return path;
    }

    /**
     * 播放回调接口
     */
    public interface PlaybackCallback {
        void onTaskStarted(long taskId);
        void onTaskStopped(long taskId);
        void onPlaybackStateChanged(PlaybackState state);
    }
    
    /**
     * 播放状态数据类
     */
    public static class PlaybackState {
        public final boolean isPlaying;
        public final boolean isPaused;
        public final boolean isRandomPausing;      // 是否处于随机暂停中
        public final long remainingPauseMillis;    // 剩余暂停时间（毫秒）
        public final String currentSongName;
        public final String taskName;
        public final long taskId;
        public final int currentPosition;
        public final int duration;
        
        public PlaybackState(boolean isPlaying, boolean isPaused, boolean isRandomPausing,
                           long remainingPauseMillis, String currentSongName, 
                           String taskName, long taskId, int currentPosition, int duration) {
            this.isPlaying = isPlaying;
            this.isPaused = isPaused;
            this.isRandomPausing = isRandomPausing;
            this.remainingPauseMillis = remainingPauseMillis;
            this.currentSongName = currentSongName;
            this.taskName = taskName;
            this.taskId = taskId;
            this.currentPosition = currentPosition;
            this.duration = duration;
        }
    }
    
    /**
     * 获取当前播放状态
     */
    public PlaybackState getPlaybackState() {
        if (taskPlayers.isEmpty()) {
            return new PlaybackState(false, false, false, 0, null, null, -1, 0, 0);
        }
        
        // 获取第一个正在播放的任务
        for (Map.Entry<Long, TaskPlayer> entry : taskPlayers.entrySet()) {
            TaskPlayer player = entry.getValue();
            if (player != null && player.isPlaying) {
                // 使用 isPlaying 标志而不是 isActuallyPlaying()，因为 MediaPlayer 准备期间 isActuallyPlaying() 可能返回 false
                boolean actuallyPlaying = player.isActuallyPlaying() || (!player.isPaused && player.isPlaying && !player.isRandomPausing);
                return new PlaybackState(
                        actuallyPlaying,
                        player.isPaused,
                        player.isRandomPausing,
                        player.getRemainingPauseMillis(),
                        player.getCurrentSongName(),
                        player.task.getName(),
                        player.task.getId(),
                        player.getCurrentPosition(),
                        player.getDuration()
                );
            }
        }
        
        return new PlaybackState(false, false, false, 0, null, null, -1, 0, 0);
    }
    
    /**
     * 暂停当前播放
     */
    public void pausePlayback() {
        for (TaskPlayer player : taskPlayers.values()) {
            if (player != null && player.isActuallyPlaying()) {
                player.pause();
                notifyPlaybackStateChanged();
                break;
            }
        }
    }
    
    /**
     * 恢复播放
     */
    public void resumePlayback() {
        for (TaskPlayer player : taskPlayers.values()) {
            if (player != null && player.isPaused) {
                player.resume();
                notifyPlaybackStateChanged();
                break;
            }
        }
    }
    
    /**
     * 跳过随机暂停，立即播放下一首
     */
    public void skipRandomPause() {
        for (TaskPlayer player : taskPlayers.values()) {
            if (player != null && player.isRandomPausing) {
                player.skipPause();
                notifyPlaybackStateChanged();
                break;
            }
        }
    }
    
    private void notifyPlaybackStateChanged() {
        if (playbackCallback != null) {
            playbackCallback.onPlaybackStateChanged(getPlaybackState());
        }
    }

    /**
     * 静态方法：启动任务播放
     */
    public static void startTaskPlayback(Context context, long taskId) {
        Intent intent = new Intent(context, AudioPlaybackService.class);
        intent.setAction(ACTION_START_TASK);
        intent.putExtra(EXTRA_TASK_ID, taskId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /**
     * 静态方法：停止任务播放
     */
    public static void stopTaskPlayback(Context context, long taskId) {
        Intent intent = new Intent(context, AudioPlaybackService.class);
        intent.setAction(ACTION_STOP_TASK);
        intent.putExtra(EXTRA_TASK_ID, taskId);
        context.startService(intent);
    }

    /**
     * 静态方法：停止所有播放
     */
    public static void stopAllPlayback(Context context) {
        Intent intent = new Intent(context, AudioPlaybackService.class);
        intent.setAction(ACTION_STOP_ALL);
        context.startService(intent);
    }
}
