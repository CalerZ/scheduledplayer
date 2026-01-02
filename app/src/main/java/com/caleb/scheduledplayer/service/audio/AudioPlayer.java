package com.caleb.scheduledplayer.service.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * 音频播放器封装
 * 封装 MediaPlayer，提供简化的播放控制接口
 */
public class AudioPlayer implements MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener, MediaPlayer.OnPreparedListener {
    
    private static final String TAG = "AudioPlayer";
    
    public interface OnPlaybackListener {
        void onPlaybackStarted(String filePath);
        void onPlaybackCompleted(String filePath);
        void onPlaybackError(String filePath, String error);
        void onPlaybackPaused(String filePath);
        void onPlaybackResumed(String filePath);
    }
    
    private final Context context;
    private MediaPlayer mediaPlayer;
    private final AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private OnPlaybackListener listener;
    
    private String currentFilePath;
    private boolean isPrepared;
    private boolean isPaused;
    private float volume = 1.0f;
    
    public AudioPlayer(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }
    
    /**
     * 设置播放监听器
     */
    public void setOnPlaybackListener(OnPlaybackListener listener) {
        this.listener = listener;
    }
    
    /**
     * 播放音频文件
     */
    public boolean play(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            notifyError(filePath, "文件路径为空");
            return false;
        }
        
        // 检查文件是否存在
        if (!isFileValid(filePath)) {
            notifyError(filePath, "文件不存在或无法访问");
            return false;
        }
        
        // 请求音频焦点
        if (!requestAudioFocus()) {
            Log.w(TAG, "无法获取音频焦点");
        }
        
        // 释放之前的播放器
        release();
        
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setOnCompletionListener(this);
            mediaPlayer.setOnErrorListener(this);
            mediaPlayer.setOnPreparedListener(this);
            
            // 设置音频属性
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            mediaPlayer.setAudioAttributes(audioAttributes);
            
            // 设置数据源
            if (filePath.startsWith("content://")) {
                mediaPlayer.setDataSource(context, Uri.parse(filePath));
            } else {
                mediaPlayer.setDataSource(filePath);
            }
            
            currentFilePath = filePath;
            isPrepared = false;
            isPaused = false;
            
            // 异步准备
            mediaPlayer.prepareAsync();
            
            return true;
        } catch (IOException e) {
            Log.e(TAG, "设置数据源失败: " + e.getMessage());
            notifyError(filePath, "无法播放文件: " + e.getMessage());
            release();
            return false;
        } catch (Exception e) {
            Log.e(TAG, "播放失败: " + e.getMessage());
            notifyError(filePath, "播放失败: " + e.getMessage());
            release();
            return false;
        }
    }
    
    /**
     * 暂停播放
     */
    public void pause() {
        if (mediaPlayer != null && isPrepared && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            isPaused = true;
            if (listener != null) {
                listener.onPlaybackPaused(currentFilePath);
            }
        }
    }
    
    /**
     * 恢复播放
     */
    public void resume() {
        if (mediaPlayer != null && isPrepared && isPaused) {
            mediaPlayer.start();
            isPaused = false;
            if (listener != null) {
                listener.onPlaybackResumed(currentFilePath);
            }
        }
    }
    
    /**
     * 停止播放
     */
    public void stop() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
            } catch (IllegalStateException e) {
                Log.w(TAG, "停止播放时出错: " + e.getMessage());
            }
        }
        abandonAudioFocus();
        release();
    }
    
    /**
     * 设置音量 (0.0 - 1.0)
     */
    public void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
        if (mediaPlayer != null && isPrepared) {
            mediaPlayer.setVolume(this.volume, this.volume);
        }
    }
    
    /**
     * 获取当前播放位置（毫秒）
     */
    public int getCurrentPosition() {
        if (mediaPlayer != null && isPrepared) {
            try {
                return mediaPlayer.getCurrentPosition();
            } catch (IllegalStateException e) {
                return 0;
            }
        }
        return 0;
    }
    
    /**
     * 获取总时长（毫秒）
     */
    public int getDuration() {
        if (mediaPlayer != null && isPrepared) {
            try {
                return mediaPlayer.getDuration();
            } catch (IllegalStateException e) {
                return 0;
            }
        }
        return 0;
    }
    
    /**
     * 跳转到指定位置
     */
    public void seekTo(int position) {
        if (mediaPlayer != null && isPrepared) {
            mediaPlayer.seekTo(position);
        }
    }
    
    /**
     * 是否正在播放
     */
    public boolean isPlaying() {
        if (mediaPlayer != null && isPrepared) {
            try {
                return mediaPlayer.isPlaying();
            } catch (IllegalStateException e) {
                return false;
            }
        }
        return false;
    }
    
    /**
     * 是否已暂停
     */
    public boolean isPaused() {
        return isPaused;
    }
    
    /**
     * 获取当前播放文件路径
     */
    public String getCurrentFilePath() {
        return currentFilePath;
    }
    
    /**
     * 释放资源
     */
    public void release() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.release();
            } catch (Exception e) {
                Log.w(TAG, "释放 MediaPlayer 时出错: " + e.getMessage());
            }
            mediaPlayer = null;
        }
        isPrepared = false;
        isPaused = false;
    }
    
    @Override
    public void onPrepared(MediaPlayer mp) {
        isPrepared = true;
        mediaPlayer.setVolume(volume, volume);
        mediaPlayer.start();
        
        if (listener != null) {
            listener.onPlaybackStarted(currentFilePath);
        }
    }
    
    @Override
    public void onCompletion(MediaPlayer mp) {
        if (listener != null) {
            listener.onPlaybackCompleted(currentFilePath);
        }
    }
    
    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        String errorMsg = "播放错误: what=" + what + ", extra=" + extra;
        Log.e(TAG, errorMsg);
        notifyError(currentFilePath, errorMsg);
        release();
        return true;
    }
    
    private void notifyError(String filePath, String error) {
        if (listener != null) {
            listener.onPlaybackError(filePath, error);
        }
    }
    
    /**
     * 检查文件是否有效
     */
    private boolean isFileValid(String filePath) {
        if (filePath.startsWith("content://")) {
            // Content URI，尝试打开检查
            try {
                context.getContentResolver().openInputStream(Uri.parse(filePath)).close();
                return true;
            } catch (Exception e) {
                return false;
            }
        } else {
            // 普通文件路径
            File file = new File(filePath);
            return file.exists() && file.canRead();
        }
    }
    
    /**
     * 请求音频焦点
     */
    private boolean requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(audioAttributes)
                    .setOnAudioFocusChangeListener(focusChange -> {
                        // 处理音频焦点变化
                        switch (focusChange) {
                            case AudioManager.AUDIOFOCUS_LOSS:
                                // 永久失去焦点，暂停播放
                                pause();
                                break;
                            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                                // 短暂失去焦点，暂停播放
                                pause();
                                break;
                            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                                // 可以降低音量继续播放
                                setVolume(0.3f);
                                break;
                            case AudioManager.AUDIOFOCUS_GAIN:
                                // 重新获得焦点
                                setVolume(volume);
                                if (isPaused) {
                                    resume();
                                }
                                break;
                        }
                    })
                    .build();
            
            int result = audioManager.requestAudioFocus(audioFocusRequest);
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            return true;
        }
    }
    
    /**
     * 放弃音频焦点
     */
    private void abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        }
    }
}
