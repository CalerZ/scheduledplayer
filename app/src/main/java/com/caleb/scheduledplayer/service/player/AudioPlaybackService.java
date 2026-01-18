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

import android.bluetooth.BluetoothDevice;

import com.caleb.scheduledplayer.util.AppLogger;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.caleb.scheduledplayer.ScheduledPlayerApp;
import com.caleb.scheduledplayer.R;
import com.caleb.scheduledplayer.data.converter.Converters;
import com.caleb.scheduledplayer.data.database.AppDatabase;
import com.caleb.scheduledplayer.data.entity.TaskEntity;
import com.caleb.scheduledplayer.data.repository.TaskLogRepository;
import com.caleb.scheduledplayer.presentation.ui.main.MainActivity;
import com.caleb.scheduledplayer.service.scheduler.TaskSchedulerService;
import com.caleb.scheduledplayer.util.AudioFileValidator;
import com.caleb.scheduledplayer.util.BluetoothHelper;
import com.caleb.scheduledplayer.util.BluetoothReconnectManager;
import com.caleb.scheduledplayer.util.AppSettings;
import com.caleb.scheduledplayer.util.LogErrorType;

import android.content.SharedPreferences;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.Permission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 音频播放前台服务
 * 支持多任务同时播放（混音）
 */
public class AudioPlaybackService extends Service {

    private static final String TAG = "AudioPlaybackService";
    private static final int NOTIFICATION_ID = 1;
    private static final int BLUETOOTH_NOTIFICATION_ID = 2;
    
    // 静态变量：跟踪 Service 是否正在运行
    // 用于判断是否需要在 handleReboot 时恢复播放
    private static volatile boolean isServiceRunning = false;
    
    // 静态变量：跟踪正在播放的任务ID
    // 用于外部检查特定任务是否正在播放
    private static final Set<Long> playingTaskIds = Collections.synchronizedSet(new HashSet<>());

    // Intent Actions
    public static final String ACTION_START_TASK = "com.caleb.scheduledplayer.START_TASK";
    public static final String ACTION_STOP_TASK = "com.caleb.scheduledplayer.STOP_TASK";
    public static final String ACTION_STOP_ALL = "com.caleb.scheduledplayer.STOP_ALL";
    public static final String EXTRA_TASK_ID = "task_id";

    private final IBinder binder = new LocalBinder();
    private final Map<Long, TaskPlayer> taskPlayers = new ConcurrentHashMap<>();
    private final Map<Long, Long> taskLogIds = new ConcurrentHashMap<>();  // 任务ID -> 日志ID映射
    private final Map<Long, Integer> taskOutputDevices = new ConcurrentHashMap<>();  // 任务ID -> 输出设备映射
    private final List<Long> bluetoothPausedTasks = new ArrayList<>();  // 因蓝牙断开而暂停的任务
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService executorService;
    private PowerManager.WakeLock wakeLock;
    private static final long WAKELOCK_REFRESH_INTERVAL = 30 * 60 * 1000L; // 30分钟刷新一次
    private final Runnable wakeLockRefreshRunnable = this::refreshWakeLock;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private boolean hasAudioFocus = false;
    private TaskLogRepository logRepository;
    private BluetoothHelper bluetoothHelper;
    
    // 蓝牙保持和重连相关
    private AppSettings appSettings;
    private SilentAudioPlayer silentAudioPlayer;
    private BluetoothReconnectManager reconnectManager;
    
    // 播放状态持久化相关
    private static final String PREFS_NAME = "playback_state";
    private static final String KEY_PLAYING_TASK_IDS = "playing_task_ids";
    private static final String KEY_TASK_CURRENT_INDEX = "task_%d_current_index";
    private static final String KEY_TASK_CURRENT_POSITION = "task_%d_current_position";
    private static final String KEY_TASK_PLAYLIST = "task_%d_playlist";
    private SharedPreferences playbackPrefs;

    private PlaybackCallback playbackCallback;

    public class LocalBinder extends Binder {
        public AudioPlaybackService getService() {
            return AudioPlaybackService.this;
        }
    }
    
    /**
     * 静音音频播放状态
     */
    public enum SilentAudioStatus {
        PLAYING,      // 播放中
        STOPPED,      // 已停止
        DISABLED,     // 功能已关闭
        NO_BLUETOOTH  // 未连接蓝牙
    }
    
    /**
     * 获取静音音频播放状态
     */
    public SilentAudioStatus getSilentAudioStatus() {
        if (!appSettings.isKeepBluetoothAlive()) {
            return SilentAudioStatus.DISABLED;
        }
        if (!bluetoothHelper.isBluetoothAudioConnected()) {
            return SilentAudioStatus.NO_BLUETOOTH;
        }
        if (silentAudioPlayer == null) {
            return SilentAudioStatus.STOPPED;
        }
        if (silentAudioPlayer.isPlaying()) {
            return SilentAudioStatus.PLAYING;
        }
        return SilentAudioStatus.STOPPED;
    }
    
    /**
     * 获取蓝牙连接状态
     */
    public boolean isBluetoothConnected() {
        return bluetoothHelper != null && bluetoothHelper.isBluetoothAudioConnected();
    }
    
    /**
     * 获取当前连接的蓝牙设备名称
     */
    public String getConnectedBluetoothDeviceName() {
        if (bluetoothHelper != null) {
            BluetoothDevice device = bluetoothHelper.getLastConnectedDevice();
            if (device != null) {
                try {
                    return device.getName();
                } catch (SecurityException e) {
                    return null;
                }
            }
        }
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        isServiceRunning = true;  // 标记 Service 正在运行
        AppLogger.getInstance().d(TAG, "onCreate: Service created, pid=" + android.os.Process.myPid() + ", isServiceRunning=true");
        executorService = Executors.newFixedThreadPool(4);  // 限制线程数量，避免线程爆炸
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        logRepository = new TaskLogRepository(getApplication());
        bluetoothHelper = new BluetoothHelper(this);
        playbackPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        acquireWakeLock();
        
        // 初始化设置和蓝牙保持组件
        appSettings = new AppSettings(this);
        silentAudioPlayer = new SilentAudioPlayer(this);
        reconnectManager = new BluetoothReconnectManager(this, bluetoothHelper);
        
        // 开始监听蓝牙连接状态
        bluetoothHelper.startListening(new BluetoothHelper.BluetoothConnectionListener() {
            @Override
            public void onBluetoothAudioConnected() {
                AppLogger.getInstance().d(TAG, "Bluetooth audio connected");
            }

            @Override
            public void onBluetoothAudioDisconnected() {
                AppLogger.getInstance().d(TAG, "Bluetooth audio disconnected");
                // 停止所有需要蓝牙的任务
                mainHandler.post(() -> stopBluetoothOnlyTasks());
            }
            
            @Override
            public void onBluetoothDeviceDisconnected(BluetoothDevice device, String deviceName) {
                AppLogger.getInstance().d(TAG, "Bluetooth device disconnected: " + deviceName);
                mainHandler.post(() -> handleBluetoothDisconnected(device, deviceName));
            }
            
            @Override
            public void onBluetoothDeviceConnected(BluetoothDevice device, String deviceName) {
                AppLogger.getInstance().d(TAG, "Bluetooth device connected: " + deviceName);
                mainHandler.post(() -> handleBluetoothConnected(device, deviceName));
            }
        });
        
        // 初始化蓝牙保持活跃
        initBluetoothKeepAlive();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : "null";
        long intentTaskId = intent != null ? intent.getLongExtra(EXTRA_TASK_ID, -1) : -1;
        AppLogger.getInstance().d(TAG, "onStartCommand: flags=" + flags + ", startId=" + startId + 
              ", action=" + action + ", taskId=" + intentTaskId);
        // 立即启动前台服务，避免 ANR
        startForeground(NOTIFICATION_ID, createNotification());
        
        if (intent != null) {
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
        isServiceRunning = false;  // 标记 Service 不再运行
        playingTaskIds.clear();    // 清空正在播放的任务集合
        AppLogger.getInstance().d(TAG, "onDestroy called, taskPlayers count: " + taskPlayers.size() + ", isServiceRunning=false");
        stopAllTasks();
        releaseWakeLock();
        abandonAudioFocus();
        if (bluetoothHelper != null) {
            bluetoothHelper.stopListening();
        }
        if (silentAudioPlayer != null) {
            silentAudioPlayer.stop();
            silentAudioPlayer.release();
        }
        if (reconnectManager != null) {
            reconnectManager.release();
        }
        if (executorService != null) {
            executorService.shutdown();
        }
    }
    
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        AppLogger.getInstance().d(TAG, "onTaskRemoved: app removed from recent tasks");
        // 当应用从最近任务列表移除时，重新启动服务以保持播放
        if (!taskPlayers.isEmpty()) {
            AppLogger.getInstance().d(TAG, "Restarting service to continue playback");
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
        playingTaskIds.remove(taskId);  // 从正在播放的任务集合移除
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
        playingTaskIds.clear();  // 清空正在播放的任务集合
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
        AppLogger.getInstance().d(TAG, "startTaskPlayback called for task " + task.getId() + " [" + task.getName() + "]");
        
        // 检查蓝牙播放模式
        if (task.getOutputDevice() == TaskEntity.OUTPUT_DEVICE_BLUETOOTH) {
            if (!bluetoothHelper.isBluetoothAudioConnected()) {
                AppLogger.getInstance().w(TAG, "Task " + task.getId() + " requires bluetooth but no bluetooth audio connected");
                // 记录失败日志
                executorService.execute(() -> {
                    long logId = logRepository.createLog(task.getId());
                    logRepository.updateLogFailed(logId, LogErrorType.BLUETOOTH_NOT_CONNECTED, "蓝牙音频设备未连接");
                });
                return;
            }
        }
        
        // 检查是否已经在播放（防止进程重启后重复启动）
        // 注意：playingTaskIds 在进程重启后会清空，所以也要检查 taskPlayers
        if (playingTaskIds.contains(task.getId())) {
            // 已经标记为正在播放，检查是否真的在播放
            if (taskPlayers.containsKey(task.getId())) {
                TaskPlayer existingPlayer = taskPlayers.get(task.getId());
                List<String> newAudioPaths = Converters.parseAudioPaths(task.getAudioPaths());
                
                // 如果音频列表没变，继续播放，不重启
                if (existingPlayer != null && existingPlayer.isSamePlaylist(newAudioPaths)) {
                    AppLogger.getInstance().d(TAG, "Task " + task.getId() + " is already playing with same playlist, skipping restart");
                    return;
                }
                
                // 音频列表变了，停止当前播放（但不触发服务停止）
                TaskPlayer player = taskPlayers.remove(task.getId());
                playingTaskIds.remove(task.getId());
                if (player != null) {
                    player.stopWithoutCallback();
                }
            } else {
                // playingTaskIds 中有但 taskPlayers 中没有，说明正在启动过程中
                // 这是进程重启后收到重复 Intent 的情况，直接跳过
                AppLogger.getInstance().d(TAG, "Task " + task.getId() + " is being started, skipping duplicate request");
                return;
            }
        } else if (taskPlayers.containsKey(task.getId())) {
            // taskPlayers 中有但 playingTaskIds 中没有（理论上不应该发生，但为了安全处理）
            TaskPlayer existingPlayer = taskPlayers.get(task.getId());
            List<String> newAudioPaths = Converters.parseAudioPaths(task.getAudioPaths());
            
            if (existingPlayer != null && existingPlayer.isSamePlaylist(newAudioPaths)) {
                AppLogger.getInstance().d(TAG, "Task " + task.getId() + " is already playing (taskPlayers), adding to playingTaskIds and skipping restart");
                playingTaskIds.add(task.getId());
                return;
            }
            
            TaskPlayer player = taskPlayers.remove(task.getId());
            if (player != null) {
                player.stopWithoutCallback();
            }
        }
        
        // 立即标记任务为"正在启动"，防止进程重启后收到重复 Intent 时重复启动
        // 这必须在任何可能返回之前执行
        playingTaskIds.add(task.getId());
        AppLogger.getInstance().d(TAG, "Marked task " + task.getId() + " as starting, playingTaskIds size: " + playingTaskIds.size());

        // 请求音频焦点
        if (!hasAudioFocus) {
            requestAudioFocus();
        }

        // 解析音频路径
        List<String> audioPaths = Converters.parseAudioPaths(task.getAudioPaths());
        if (audioPaths.isEmpty()) {
            AppLogger.getInstance().w(TAG, "Task " + task.getId() + " has no audio files, will retry in 5 minutes");
            // 移除标记，因为任务启动失败
            playingTaskIds.remove(task.getId());
            // 记录失败日志
            executorService.execute(() -> {
                long logId = logRepository.createLog(task.getId());
                logRepository.updateLogFailed(logId, LogErrorType.FILE_MISSING, "没有可播放的音频文件");
            });
            // 延迟 5 分钟重试（可能是存储还未挂载或文件暂时不可用）
            final long taskId = task.getId();
            mainHandler.postDelayed(() -> startTask(taskId), 5 * 60 * 1000L);
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

        // 检查是否有保存的播放状态（崩溃恢复场景）
        int[] savedState = null;
        List<String> savedPlaylist = null;
        if (hasSavedPlaybackState(task.getId())) {
            savedState = getSavedPlaybackState(task.getId());
            savedPlaylist = getSavedPlaylist(task.getId());
            // 验证保存的播放列表是否与当前一致
            if (savedPlaylist != null && !savedPlaylist.isEmpty()) {
                // 检查播放列表是否相同（忽略顺序）
                boolean samePlaylist = savedPlaylist.size() == audioPaths.size() 
                        && savedPlaylist.containsAll(audioPaths) 
                        && audioPaths.containsAll(savedPlaylist);
                if (samePlaylist) {
                    AppLogger.getInstance().d(TAG, "Recovering from crash for task " + task.getId() + 
                          ": index=" + savedState[0] + ", position=" + savedState[1]);
                } else {
                    AppLogger.getInstance().d(TAG, "Playlist changed for task " + task.getId() + ", starting from beginning");
                    savedState = null;
                    savedPlaylist = null;
                    clearTaskPlaybackState(task.getId());
                }
            } else {
                savedState = null;
            }
        }

        // 创建任务播放器
        TaskPlayer player;
        if (savedState != null && savedPlaylist != null) {
            // 崩溃恢复：使用保存的播放列表和位置
            player = new TaskPlayer(task, savedPlaylist, savedState[0], savedState[1]);
        } else {
            // 正常启动：从头开始
            player = new TaskPlayer(task, audioPaths);
        }
        taskPlayers.put(task.getId(), player);
        // playingTaskIds 已在前面添加，此处不需要重复添加
        player.start();
        
        // 暂停静音音频
        onTaskPlaybackStarted();

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
        
        // 清空并重新记录因蓝牙断开而停止的任务
        bluetoothPausedTasks.clear();
        
        for (Long taskId : tasksToStop) {
            AppLogger.getInstance().d(TAG, "Stopping bluetooth-only task " + taskId + " due to bluetooth disconnection");
            
            // 记录被暂停的任务，以便蓝牙重连后恢复
            bluetoothPausedTasks.add(taskId);
            
            // 记录日志
            Long logId = taskLogIds.get(taskId);
            if (logId != null) {
                executorService.execute(() -> {
                    logRepository.updateLogFailed(logId, LogErrorType.BLUETOOTH_DISCONNECTED, "蓝牙音频设备断开连接，等待重连");
                });
            }
            
            stopTask(taskId);
        }
        
        AppLogger.getInstance().d(TAG, "Recorded " + bluetoothPausedTasks.size() + " tasks to resume when bluetooth reconnects");
    }
    
    /**
     * 恢复因蓝牙断开而停止的任务
     */
    private void resumeBluetoothPausedTasks() {
        if (bluetoothPausedTasks.isEmpty()) {
            AppLogger.getInstance().d(TAG, "No bluetooth-paused tasks to resume");
            return;
        }
        
        AppLogger.getInstance().d(TAG, "Resuming " + bluetoothPausedTasks.size() + " bluetooth-paused tasks");
        
        // 复制列表，避免并发修改
        List<Long> tasksToResume = new ArrayList<>(bluetoothPausedTasks);
        bluetoothPausedTasks.clear();
        
        for (Long taskId : tasksToResume) {
            // 检查任务是否仍应活跃
            executorService.execute(() -> {
                TaskEntity task = AppDatabase.getInstance(this).taskDao().getTaskByIdSync(taskId);
                if (task != null && task.isEnabled() && TaskSchedulerService.shouldTaskBeActiveNow(task)) {
                    AppLogger.getInstance().d(TAG, "Resuming bluetooth task " + taskId);
                    mainHandler.post(() -> startTask(taskId));
                } else {
                    AppLogger.getInstance().d(TAG, "Task " + taskId + " no longer needs to be active, not resuming");
                }
            });
        }
    }
    
    // ==================== 蓝牙保持和重连功能 ====================
    
    /**
     * 初始化蓝牙保持活跃功能
     */
    private void initBluetoothKeepAlive() {
        if (appSettings.isKeepBluetoothAlive() && bluetoothHelper.isBluetoothAudioConnected()) {
            // 始终启动静音播放（不受任务播放状态影响）
            silentAudioPlayer.start();
            AppLogger.getInstance().d(TAG, "Started silent audio for bluetooth keep-alive");
        }
    }
    
    /**
     * 处理蓝牙断开事件
     */
    private void handleBluetoothDisconnected(BluetoothDevice device, String deviceName) {
        // 1. 停止静音播放
        silentAudioPlayer.stop();
        
        // 2. 发送通知
        boolean hasPlayingTasks = !taskPlayers.isEmpty();
        showBluetoothDisconnectedNotification(deviceName, hasPlayingTasks);
        
        // 3. 尝试自动重连
        if (appSettings.isBluetoothAutoReconnect() && device != null) {
            reconnectManager.saveDisconnectedDevice(device);
            reconnectManager.startReconnect(new BluetoothReconnectManager.ReconnectCallback() {
                @Override
                public void onReconnectSuccess() {
                    AppLogger.getInstance().d(TAG, "Bluetooth reconnect successful");
                }
                
                @Override
                public void onReconnectFailed(String reason) {
                    AppLogger.getInstance().d(TAG, "Bluetooth reconnect failed: " + reason);
                }
                
                @Override
                public void onReconnectAttempt(int attempt, int maxAttempts) {
                    AppLogger.getInstance().d(TAG, "Bluetooth reconnect attempt " + attempt + "/" + maxAttempts);
                }
            });
        }
    }
    
    /**
     * 处理蓝牙连接事件
     */
    private void handleBluetoothConnected(BluetoothDevice device, String deviceName) {
        // 1. 停止重连尝试
        reconnectManager.stopReconnect();
        
        // 2. 发送通知
        showBluetoothConnectedNotification(deviceName);
        
        // 3. 恢复静音播放（始终启动，不受任务播放状态影响）
        if (appSettings.isKeepBluetoothAlive()) {
            silentAudioPlayer.start();
            AppLogger.getInstance().d(TAG, "Resumed silent audio after bluetooth reconnect");
        }
        
        // 4. 恢复因蓝牙断开而停止的任务
        resumeBluetoothPausedTasks();
    }
    
    /**
     * 显示蓝牙断开通知
     */
    private void showBluetoothDisconnectedNotification(String deviceName, boolean hasPlayingTasks) {
        String title = getString(R.string.bluetooth_disconnected);
        String content;
        if (hasPlayingTasks) {
            content = getString(R.string.bluetooth_disconnected_audio_switched);
        } else {
            content = getString(R.string.bluetooth_disconnected_device, deviceName);
        }
        
        showBluetoothNotification(title, content);
    }
    
    /**
     * 显示蓝牙连接通知
     */
    private void showBluetoothConnectedNotification(String deviceName) {
        String title = getString(R.string.bluetooth_connected);
        String content = getString(R.string.bluetooth_connected_device, deviceName);
        
        showBluetoothNotification(title, content);
    }
    
    /**
     * 显示蓝牙状态通知
     */
    private void showBluetoothNotification(String title, String content) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        Notification notification = new NotificationCompat.Builder(this, ScheduledPlayerApp.NOTIFICATION_CHANNEL_PLAYBACK)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(R.drawable.ic_bluetooth_speaker)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();
        
        android.app.NotificationManager notificationManager = 
                (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.notify(BLUETOOTH_NOTIFICATION_ID, notification);
        }
    }
    
    /**
     * 设置变更回调（从 SettingsActivity 调用）
     */
    public void onSettingsChanged() {
        AppLogger.getInstance().d(TAG, "Settings changed, updating bluetooth keep-alive state");
        
        if (appSettings.isKeepBluetoothAlive()) {
            // 开启保持蓝牙活跃（始终启动，不受任务播放状态影响）
            if (bluetoothHelper.isBluetoothAudioConnected()) {
                silentAudioPlayer.start();
            }
        } else {
            // 关闭保持蓝牙活跃
            silentAudioPlayer.stop();
        }
    }
    
    /**
     * 任务开始播放时（静音音频保持播放，不暂停）
     */
    private void onTaskPlaybackStarted() {
        // 静音音频持续播放，不暂停，以保持蓝牙连接稳定性
        AppLogger.getInstance().d(TAG, "Task playback started, silent audio keeps playing");
    }
    
    /**
     * 所有任务播放结束时
     */
    private void onAllTasksPlaybackStopped() {
        // 静音音频持续播放，无需恢复
        AppLogger.getInstance().d(TAG, "All tasks stopped, silent audio keeps playing");
    }

    private void updateNotificationOrStop() {
        if (taskPlayers.isEmpty()) {
            // 所有任务播放结束，恢复静音音频
            onAllTasksPlaybackStopped();
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
        }
        // 每次获取 1 小时，定期刷新避免超时
        if (!wakeLock.isHeld()) {
            wakeLock.acquire(60 * 60 * 1000L);
            AppLogger.getInstance().d(TAG, "WakeLock acquired for 1 hour");
        }
        // 设置定期刷新
        mainHandler.removeCallbacks(wakeLockRefreshRunnable);
        mainHandler.postDelayed(wakeLockRefreshRunnable, WAKELOCK_REFRESH_INTERVAL);
    }
    
    /**
     * 刷新 WakeLock，防止超时释放
     */
    private void refreshWakeLock() {
        if (!taskPlayers.isEmpty()) {
            // 还有任务在播放，重新获取 WakeLock
            if (wakeLock != null) {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
                wakeLock.acquire(60 * 60 * 1000L);
                AppLogger.getInstance().d(TAG, "WakeLock refreshed for another hour");
            }
            // 继续定期刷新
            mainHandler.postDelayed(wakeLockRefreshRunnable, WAKELOCK_REFRESH_INTERVAL);
        }
    }

    private void releaseWakeLock() {
        mainHandler.removeCallbacks(wakeLockRefreshRunnable);
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            AppLogger.getInstance().d(TAG, "WakeLock released");
        }
        wakeLock = null;
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
        private static final int MAX_CONSECUTIVE_ERRORS = 10;  // 最大连续错误次数
        private static final int STATE_SAVE_INTERVAL = 5000;  // 保存状态间隔（毫秒）
        
        private final TaskEntity task;
        private final List<String> playlist;
        private final List<String> playedFiles = new ArrayList<>();  // 已播放的文件列表
        private MediaPlayer mediaPlayer;
        private int currentIndex = 0;
        private int resumePosition = 0;  // 恢复播放的位置
        private boolean isPlaying = false;
        private boolean isPaused = false;
        private int consecutiveErrors = 0;  // 连续错误计数器
        private final Runnable stateSaveRunnable = this::saveCurrentState;

        TaskPlayer(TaskEntity task, List<String> audioPaths) {
            this(task, audioPaths, 0, 0);
        }
        
        /**
         * 构造函数（支持从指定位置恢复）
         */
        TaskPlayer(TaskEntity task, List<String> audioPaths, int startIndex, int startPosition) {
            this.task = task;
            this.playlist = new ArrayList<>(audioPaths);
            this.currentIndex = startIndex;
            this.resumePosition = startPosition;

            // 如果是随机模式且没有保存的状态，打乱播放列表
            if (task.getPlayMode() == TaskEntity.PLAY_MODE_RANDOM && startIndex == 0 && startPosition == 0) {
                Collections.shuffle(this.playlist);
            }
            
            // 验证起始索引
            if (this.currentIndex >= this.playlist.size()) {
                this.currentIndex = 0;
                this.resumePosition = 0;
            }
        }

        void start() {
            isPlaying = true;
            isPaused = false;
            playCurrentTrack();
            // 启动定期保存状态
            scheduleStateSave();
        }

        void stop() {
            isPlaying = false;
            isPaused = false;
            // 停止定期保存
            mainHandler.removeCallbacks(stateSaveRunnable);
            // 清除保存的状态
            clearTaskPlaybackState(task.getId());
            releaseMediaPlayer();
            if (playbackCallback != null) {
                playbackCallback.onTaskStopped(task.getId());
            }
        }
        
        void pause() {
            if (mediaPlayer != null && isActuallyPlaying()) {
                mediaPlayer.pause();
                isPaused = true;
                // 暂停时保存状态
                saveCurrentState();
            }
        }
        
        void resume() {
            if (mediaPlayer != null && isPaused) {
                mediaPlayer.start();
                isPaused = false;
            }
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
            mainHandler.removeCallbacks(stateSaveRunnable);
            clearTaskPlaybackState(task.getId());
            releaseMediaPlayer();
        }
        
        /**
         * 定期保存播放状态
         */
        private void scheduleStateSave() {
            mainHandler.removeCallbacks(stateSaveRunnable);
            if (isPlaying) {
                mainHandler.postDelayed(stateSaveRunnable, STATE_SAVE_INTERVAL);
            }
        }
        
        /**
         * 保存当前播放状态
         */
        private void saveCurrentState() {
            if (isPlaying && !playlist.isEmpty()) {
                int position = getCurrentPosition();
                saveTaskPlaybackState(task.getId(), currentIndex, position, playlist);
                // 继续定期保存
                scheduleStateSave();
            }
        }

        /**
         * 检查播放列表是否相同
         */
        boolean isSamePlaylist(List<String> otherPaths) {
            if (otherPaths == null) {
                AppLogger.getInstance().d(TAG, "isSamePlaylist: otherPaths is null");
                return false;
            }
            if (otherPaths.size() != playlist.size()) {
                AppLogger.getInstance().d(TAG, "isSamePlaylist: size mismatch - playlist=" + playlist.size() + ", other=" + otherPaths.size());
                return false;
            }
            // 比较内容（忽略顺序，因为可能是随机模式）
            boolean result = playlist.containsAll(otherPaths) && otherPaths.containsAll(playlist);
            if (!result) {
                AppLogger.getInstance().d(TAG, "isSamePlaylist: content mismatch - playlist=" + playlist + ", other=" + otherPaths);
            }
            return result;
        }

        /**
         * 获取已播放的文件列表
         */
        List<String> getPlayedFiles() {
            return new ArrayList<>(playedFiles);
        }

        private void playCurrentTrack() {
            AppLogger.getInstance().d(TAG, "playCurrentTrack: isPlaying=" + isPlaying + ", playlistSize=" + playlist.size() + ", currentIndex=" + currentIndex + ", resumePosition=" + resumePosition);
            if (!isPlaying || playlist.isEmpty()) {
                AppLogger.getInstance().w(TAG, "playCurrentTrack: skipping because isPlaying=" + isPlaying + " or playlist is empty");
                return;
            }
            
            // 检查连续错误次数，防止无限循环
            if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                AppLogger.getInstance().e(TAG, "Too many consecutive errors (" + consecutiveErrors + "), stopping task " + task.getId());
                recordPlaybackError(task.getId(), LogErrorType.PLAYER_ERROR, 
                        "连续播放失败次数过多，已停止任务");
                isPlaying = false;
                mainHandler.post(() -> stopTask(task.getId()));
                return;
            }

            if (currentIndex >= playlist.size()) {
                // 播放列表结束，循环播放（所有模式都循环，直到任务结束时间）
                currentIndex = 0;
                resumePosition = 0;  // 重置恢复位置
                // 随机模式下，重新洗牌
                if (task.getPlayMode() == TaskEntity.PLAY_MODE_RANDOM) {
                    Collections.shuffle(playlist);
                }
            }

            String audioPath = playlist.get(currentIndex);
            final int seekPosition = resumePosition;  // 保存恢复位置
            resumePosition = 0;  // 只在第一次播放时使用恢复位置
            AppLogger.getInstance().d(TAG, "playCurrentTrack: playing " + audioPath + (seekPosition > 0 ? " from position " + seekPosition : ""));
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
                    AppLogger.getInstance().d(TAG, "MediaPlayer onInfo: what=" + what + ", extra=" + extra);
                    return false;
                });
                mediaPlayer.prepareAsync();
                mediaPlayer.setOnPreparedListener(mp -> {
                    AppLogger.getInstance().d(TAG, "MediaPlayer prepared, isPlaying=" + isPlaying);
                    if (isPlaying) {
                        try {
                            // 如果有恢复位置，先 seek 到指定位置
                            if (seekPosition > 0) {
                                mp.seekTo(seekPosition);
                                AppLogger.getInstance().d(TAG, "MediaPlayer seeking to position: " + seekPosition);
                            }
                            mp.start();
                            AppLogger.getInstance().d(TAG, "MediaPlayer started playing: " + audioPath + 
                                  ", duration=" + mp.getDuration() + "ms, isActuallyPlaying=" + mp.isPlaying() +
                                  (seekPosition > 0 ? ", resumed from " + seekPosition + "ms" : ""));
                            // 播放成功，重置连续错误计数
                            consecutiveErrors = 0;
                            // 记录已播放的文件
                            playedFiles.add(audioPath);
                            // 立即保存状态
                            saveCurrentState();
                            // 通知播放状态变化
                            notifyPlaybackStateChanged();
                        } catch (IllegalStateException e) {
                            AppLogger.getInstance().e(TAG, "Failed to start MediaPlayer", e);
                            consecutiveErrors++;
                            currentIndex++;
                            playCurrentTrack();
                        }
                    }
                });

            } catch (SecurityException e) {
                AppLogger.getInstance().e(TAG, "Permission denied for audio: " + audioPath, e);
                // 记录权限错误到日志
                recordPlaybackError(task.getId(), LogErrorType.PERMISSION_DENIED, 
                        "权限被拒绝: " + getFileName(audioPath));
                // 跳到下一首
                consecutiveErrors++;
                currentIndex++;
                playCurrentTrack();
            } catch (IOException e) {
                AppLogger.getInstance().e(TAG, "Error playing audio: " + audioPath, e);
                // 记录文件缺失错误
                recordPlaybackError(task.getId(), LogErrorType.FILE_MISSING, 
                        "文件不存在: " + getFileName(audioPath));
                // 跳到下一首
                consecutiveErrors++;
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
                    AppLogger.getInstance().e(TAG, "Error releasing MediaPlayer", e);
                }
                mediaPlayer = null;
            }
        }

        @Override
        public void onCompletion(MediaPlayer mp) {
            AppLogger.getInstance().d(TAG, "onCompletion: track finished, currentIndex=" + currentIndex);
            // 播放成功完成，重置连续错误计数
            consecutiveErrors = 0;
            currentIndex++;
            playCurrentTrack();
        }

        @Override
        public boolean onError(MediaPlayer mp, int what, int extra) {
            AppLogger.getInstance().e(TAG, "MediaPlayer error: what=" + what + ", extra=" + extra);
            // 记录播放器错误
            recordPlaybackError(task.getId(), LogErrorType.PLAYER_ERROR, 
                    "播放器错误: what=" + what + ", extra=" + extra);
            consecutiveErrors++;
            currentIndex++;
            playCurrentTrack();
            return true;
        }
    }

    /**
     * 记录播放错误到日志（仅记录，不更新状态为失败）
     */
    private void recordPlaybackError(long taskId, int errorType, String errorMessage) {
        AppLogger.getInstance().w(TAG, "Playback error for task " + taskId + ": " + errorMessage);
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
    
    // ==================== 播放状态持久化 ====================
    
    /**
     * 保存任务的播放状态（用于崩溃恢复）
     */
    private void saveTaskPlaybackState(long taskId, int currentIndex, int currentPosition, List<String> playlist) {
        SharedPreferences.Editor editor = playbackPrefs.edit();
        editor.putInt(String.format(KEY_TASK_CURRENT_INDEX, taskId), currentIndex);
        editor.putInt(String.format(KEY_TASK_CURRENT_POSITION, taskId), currentPosition);
        // 保存播放列表（用逗号分隔）
        editor.putString(String.format(KEY_TASK_PLAYLIST, taskId), String.join("|||", playlist));
        // 更新正在播放的任务ID列表
        Set<String> taskIds = new HashSet<>(playbackPrefs.getStringSet(KEY_PLAYING_TASK_IDS, new HashSet<>()));
        taskIds.add(String.valueOf(taskId));
        editor.putStringSet(KEY_PLAYING_TASK_IDS, taskIds);
        editor.apply();
        AppLogger.getInstance().d(TAG, "Saved playback state for task " + taskId + ": index=" + currentIndex + ", position=" + currentPosition);
    }
    
    /**
     * 获取保存的播放状态
     */
    private int[] getSavedPlaybackState(long taskId) {
        int currentIndex = playbackPrefs.getInt(String.format(KEY_TASK_CURRENT_INDEX, taskId), 0);
        int currentPosition = playbackPrefs.getInt(String.format(KEY_TASK_CURRENT_POSITION, taskId), 0);
        return new int[]{currentIndex, currentPosition};
    }
    
    /**
     * 获取保存的播放列表
     */
    private List<String> getSavedPlaylist(long taskId) {
        String playlistStr = playbackPrefs.getString(String.format(KEY_TASK_PLAYLIST, taskId), "");
        if (playlistStr.isEmpty()) {
            return new ArrayList<>();
        }
        String[] parts = playlistStr.split("\\|\\|\\|");
        List<String> playlist = new ArrayList<>();
        for (String part : parts) {
            if (!part.isEmpty()) {
                playlist.add(part);
            }
        }
        return playlist;
    }
    
    /**
     * 清除任务的播放状态
     */
    private void clearTaskPlaybackState(long taskId) {
        SharedPreferences.Editor editor = playbackPrefs.edit();
        editor.remove(String.format(KEY_TASK_CURRENT_INDEX, taskId));
        editor.remove(String.format(KEY_TASK_CURRENT_POSITION, taskId));
        editor.remove(String.format(KEY_TASK_PLAYLIST, taskId));
        // 从正在播放的任务ID列表中移除
        Set<String> taskIds = new HashSet<>(playbackPrefs.getStringSet(KEY_PLAYING_TASK_IDS, new HashSet<>()));
        taskIds.remove(String.valueOf(taskId));
        editor.putStringSet(KEY_PLAYING_TASK_IDS, taskIds);
        editor.apply();
        AppLogger.getInstance().d(TAG, "Cleared playback state for task " + taskId);
    }
    
    /**
     * 检查是否有保存的播放状态（用于判断是否是崩溃恢复）
     */
    private boolean hasSavedPlaybackState(long taskId) {
        Set<String> taskIds = playbackPrefs.getStringSet(KEY_PLAYING_TASK_IDS, new HashSet<>());
        return taskIds.contains(String.valueOf(taskId));
    }

    /**
     * 播放回调接口
     */
    public interface PlaybackCallback {
        void onTaskStarted(long taskId);
        void onTaskStopped(long taskId);
        void onPlaybackStateChanged(PlaybackState state);
        // 新增：多任务播放状态回调
        default void onAllPlaybackStatesChanged(Map<Long, PlaybackState> states) {}
    }
    
    /**
     * 播放状态数据类
     */
    public static class PlaybackState {
        public final boolean isPlaying;
        public final boolean isPaused;
        public final String currentSongName;
        public final String taskName;
        public final long taskId;
        public final int currentPosition;
        public final int duration;
        
        public PlaybackState(boolean isPlaying, boolean isPaused, String currentSongName, 
                           String taskName, long taskId, int currentPosition, int duration) {
            this.isPlaying = isPlaying;
            this.isPaused = isPaused;
            this.currentSongName = currentSongName;
            this.taskName = taskName;
            this.taskId = taskId;
            this.currentPosition = currentPosition;
            this.duration = duration;
        }
    }
    
    /**
     * 获取当前播放状态（兼容旧接口，返回第一个播放任务）
     */
    public PlaybackState getPlaybackState() {
        if (taskPlayers.isEmpty()) {
            return new PlaybackState(false, false, null, null, -1, 0, 0);
        }
        
        // 获取第一个正在播放的任务
        for (Map.Entry<Long, TaskPlayer> entry : taskPlayers.entrySet()) {
            TaskPlayer player = entry.getValue();
            if (player != null && player.isPlaying) {
                // 使用 isPlaying 标志而不是 isActuallyPlaying()，因为 MediaPlayer 准备期间 isActuallyPlaying() 可能返回 false
                boolean actuallyPlaying = player.isActuallyPlaying() || (!player.isPaused && player.isPlaying);
                return new PlaybackState(
                        actuallyPlaying,
                        player.isPaused,
                        player.getCurrentSongName(),
                        player.task.getName(),
                        player.task.getId(),
                        player.getCurrentPosition(),
                        player.getDuration()
                );
            }
        }
        
        return new PlaybackState(false, false, null, null, -1, 0, 0);
    }
    
    /**
     * 获取所有任务的播放状态
     */
    public Map<Long, PlaybackState> getAllPlaybackStates() {
        Map<Long, PlaybackState> states = new HashMap<>();
        
        for (Map.Entry<Long, TaskPlayer> entry : taskPlayers.entrySet()) {
            TaskPlayer player = entry.getValue();
            if (player != null && player.isPlaying) {
                boolean actuallyPlaying = player.isActuallyPlaying() || (!player.isPaused && player.isPlaying);
                PlaybackState state = new PlaybackState(
                        actuallyPlaying,
                        player.isPaused,
                        player.getCurrentSongName(),
                        player.task.getName(),
                        player.task.getId(),
                        player.getCurrentPosition(),
                        player.getDuration()
                );
                states.put(entry.getKey(), state);
            }
        }
        
        return states;
    }
    
    /**
     * 暂停指定任务的播放
     */
    public void pausePlayback(long taskId) {
        TaskPlayer player = taskPlayers.get(taskId);
        if (player != null && player.isActuallyPlaying()) {
            player.pause();
            notifyPlaybackStateChanged();
        }
    }
    
    /**
     * 恢复指定任务的播放
     */
    public void resumePlayback(long taskId) {
        TaskPlayer player = taskPlayers.get(taskId);
        if (player != null && player.isPaused) {
            player.resume();
            notifyPlaybackStateChanged();
        }
    }
    
    /**
     * 暂停当前播放（兼容旧接口，暂停第一个播放的任务）
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
     * 恢复播放（兼容旧接口，恢复第一个暂停的任务）
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
    private void notifyPlaybackStateChanged() {
        if (playbackCallback != null) {
            playbackCallback.onPlaybackStateChanged(getPlaybackState());
            // 同时通知所有播放状态
            playbackCallback.onAllPlaybackStatesChanged(getAllPlaybackStates());
        }
    }

    /**
     * 静态方法：检查 Service 是否正在运行
     * 用于判断是否需要在 handleReboot 时恢复播放
     */
    public static boolean isRunning() {
        return isServiceRunning;
    }
    
    /**
     * 静态方法：检查特定任务是否正在播放
     * 用于判断是否需要恢复特定任务的播放
     */
    public static boolean isTaskCurrentlyPlaying(long taskId) {
        return playingTaskIds.contains(taskId);
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
