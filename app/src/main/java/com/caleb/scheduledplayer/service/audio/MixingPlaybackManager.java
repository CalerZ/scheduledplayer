package com.caleb.scheduledplayer.service.audio;

import android.content.Context;
import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 混音播放管理器
 * 支持多个任务同时播放，每个任务独立控制
 */
public class MixingPlaybackManager {
    
    private static final String TAG = "MixingPlaybackManager";
    
    public interface OnMixingPlaybackListener {
        void onTaskPlaybackStarted(long taskId, String filePath);
        void onTaskPlaybackCompleted(long taskId);
        void onTaskPlaybackError(long taskId, String error);
        void onAllPlaybackStopped();
    }
    
    private final Context context;
    private final Map<Long, TaskPlaybackController> taskControllers;
    private OnMixingPlaybackListener listener;
    
    public MixingPlaybackManager(Context context) {
        this.context = context.getApplicationContext();
        this.taskControllers = new ConcurrentHashMap<>();
    }
    
    /**
     * 设置监听器
     */
    public void setOnMixingPlaybackListener(OnMixingPlaybackListener listener) {
        this.listener = listener;
    }
    
    /**
     * 开始任务播放
     */
    public void startTask(long taskId, List<String> audioPaths, PlaylistManager.PlayMode playMode) {
        Log.d(TAG, "开始任务播放: taskId=" + taskId + ", 文件数=" + audioPaths.size());
        
        // 如果任务已存在，先停止
        stopTask(taskId);
        
        // 创建新的播放控制器
        TaskPlaybackController controller = new TaskPlaybackController(context, taskId, playMode);
        controller.setOnTaskPlaybackListener(new TaskPlaybackController.OnTaskPlaybackListener() {
            @Override
            public void onStarted(long taskId, String filePath) {
                if (listener != null) {
                    listener.onTaskPlaybackStarted(taskId, filePath);
                }
            }
            
            @Override
            public void onCompleted(long taskId) {
                // 任务播放完成，移除控制器
                taskControllers.remove(taskId);
                if (listener != null) {
                    listener.onTaskPlaybackCompleted(taskId);
                    if (taskControllers.isEmpty()) {
                        listener.onAllPlaybackStopped();
                    }
                }
            }
            
            @Override
            public void onError(long taskId, String error) {
                if (listener != null) {
                    listener.onTaskPlaybackError(taskId, error);
                }
            }
        });
        
        taskControllers.put(taskId, controller);
        controller.start(audioPaths);
    }
    
    /**
     * 停止任务播放
     */
    public void stopTask(long taskId) {
        TaskPlaybackController controller = taskControllers.remove(taskId);
        if (controller != null) {
            controller.stop();
            Log.d(TAG, "停止任务播放: taskId=" + taskId);
        }
    }
    
    /**
     * 暂停任务播放
     */
    public void pauseTask(long taskId) {
        TaskPlaybackController controller = taskControllers.get(taskId);
        if (controller != null) {
            controller.pause();
        }
    }
    
    /**
     * 恢复任务播放
     */
    public void resumeTask(long taskId) {
        TaskPlaybackController controller = taskControllers.get(taskId);
        if (controller != null) {
            controller.resume();
        }
    }
    
    /**
     * 停止所有任务播放
     */
    public void stopAll() {
        for (TaskPlaybackController controller : taskControllers.values()) {
            controller.stop();
        }
        taskControllers.clear();
        
        if (listener != null) {
            listener.onAllPlaybackStopped();
        }
    }
    
    /**
     * 暂停所有任务播放
     */
    public void pauseAll() {
        for (TaskPlaybackController controller : taskControllers.values()) {
            controller.pause();
        }
    }
    
    /**
     * 恢复所有任务播放
     */
    public void resumeAll() {
        for (TaskPlaybackController controller : taskControllers.values()) {
            controller.resume();
        }
    }
    
    /**
     * 任务是否正在播放
     */
    public boolean isTaskPlaying(long taskId) {
        TaskPlaybackController controller = taskControllers.get(taskId);
        return controller != null && controller.isPlaying();
    }
    
    /**
     * 是否有任务正在播放
     */
    public boolean hasActivePlayback() {
        return !taskControllers.isEmpty();
    }
    
    /**
     * 获取正在播放的任务数量
     */
    public int getActiveTaskCount() {
        return taskControllers.size();
    }
    
    /**
     * 获取任务当前播放的文件
     */
    public String getTaskCurrentFile(long taskId) {
        TaskPlaybackController controller = taskControllers.get(taskId);
        return controller != null ? controller.getCurrentFile() : null;
    }
    
    /**
     * 释放所有资源
     */
    public void release() {
        stopAll();
    }
    
    /**
     * 任务播放控制器
     * 管理单个任务的播放
     */
    private static class TaskPlaybackController implements AudioPlayer.OnPlaybackListener {
        
        interface OnTaskPlaybackListener {
            void onStarted(long taskId, String filePath);
            void onCompleted(long taskId);
            void onError(long taskId, String error);
        }
        
        private final long taskId;
        private final AudioPlayer audioPlayer;
        private final PlaylistManager playlistManager;
        private OnTaskPlaybackListener listener;
        private boolean isStopped;
        
        TaskPlaybackController(Context context, long taskId, PlaylistManager.PlayMode playMode) {
            this.taskId = taskId;
            this.audioPlayer = new AudioPlayer(context);
            this.playlistManager = new PlaylistManager();
            this.playlistManager.setPlayMode(playMode);
            this.playlistManager.setLooping(true);  // 默认循环播放
            this.audioPlayer.setOnPlaybackListener(this);
            this.isStopped = false;
        }
        
        void setOnTaskPlaybackListener(OnTaskPlaybackListener listener) {
            this.listener = listener;
        }
        
        void start(List<String> audioPaths) {
            playlistManager.setPlaylist(audioPaths);
            playNext();
        }
        
        void stop() {
            isStopped = true;
            audioPlayer.stop();
            audioPlayer.release();
        }
        
        void pause() {
            audioPlayer.pause();
        }
        
        void resume() {
            audioPlayer.resume();
        }
        
        boolean isPlaying() {
            return audioPlayer.isPlaying();
        }
        
        String getCurrentFile() {
            return playlistManager.getCurrentFile();
        }
        
        private void playNext() {
            if (isStopped) {
                return;
            }
            
            String nextFile = playlistManager.getCurrentFile();
            if (nextFile == null) {
                nextFile = playlistManager.getNextFile();
            }
            
            if (nextFile != null) {
                audioPlayer.play(nextFile);
            } else {
                // 播放列表为空或播放完毕
                if (listener != null) {
                    listener.onCompleted(taskId);
                }
            }
        }
        
        @Override
        public void onPlaybackStarted(String filePath) {
            if (listener != null) {
                listener.onStarted(taskId, filePath);
            }
        }
        
        @Override
        public void onPlaybackCompleted(String filePath) {
            if (isStopped) {
                return;
            }
            
            // 播放下一个文件
            String nextFile = playlistManager.getNextFile();
            if (nextFile != null) {
                audioPlayer.play(nextFile);
            } else {
                // 播放完毕（非循环模式）
                if (listener != null) {
                    listener.onCompleted(taskId);
                }
            }
        }
        
        @Override
        public void onPlaybackError(String filePath, String error) {
            Log.e("TaskPlaybackController", "播放错误: taskId=" + taskId + ", file=" + filePath + ", error=" + error);
            
            // 尝试播放下一个文件
            String nextFile = playlistManager.getNextFile();
            if (nextFile != null && !isStopped) {
                audioPlayer.play(nextFile);
            } else {
                if (listener != null) {
                    listener.onError(taskId, error);
                }
            }
        }
        
        @Override
        public void onPlaybackPaused(String filePath) {
            // 暂停不需要特殊处理
        }
        
        @Override
        public void onPlaybackResumed(String filePath) {
            // 恢复不需要特殊处理
        }
    }
}
