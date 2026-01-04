package com.caleb.scheduledplayer.service.player;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.util.Log;

import com.caleb.scheduledplayer.R;

/**
 * 静音音频播放器
 * 用于保持蓝牙音频通道活跃，防止蓝牙连接因无音频流而断开
 */
public class SilentAudioPlayer {

    private static final String TAG = "SilentAudioPlayer";

    private final Context context;
    private MediaPlayer mediaPlayer;
    private boolean isPlaying = false;
    private boolean isPaused = false;

    public SilentAudioPlayer(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * 开始播放静音音频（循环）
     */
    public void start() {
        if (isPlaying && !isPaused) {
            Log.d(TAG, "Already playing");
            return;
        }

        if (isPaused && mediaPlayer != null) {
            // 从暂停状态恢复
            resume();
            return;
        }

        try {
            release();
            
            mediaPlayer = MediaPlayer.create(context, R.raw.silence);
            if (mediaPlayer == null) {
                Log.e(TAG, "Failed to create MediaPlayer for silence audio");
                return;
            }

            // 设置音频属性
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());

            // 设置音量为0（静音）
            mediaPlayer.setVolume(0f, 0f);
            
            // 循环播放
            mediaPlayer.setLooping(true);

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error: what=" + what + ", extra=" + extra);
                isPlaying = false;
                isPaused = false;
                return true;
            });

            mediaPlayer.start();
            isPlaying = true;
            isPaused = false;
            Log.d(TAG, "Started playing silence audio");

        } catch (Exception e) {
            Log.e(TAG, "Error starting silence audio", e);
            isPlaying = false;
            isPaused = false;
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
            } catch (Exception e) {
                Log.e(TAG, "Error stopping MediaPlayer", e);
            }
        }
        release();
        isPlaying = false;
        isPaused = false;
        Log.d(TAG, "Stopped playing silence audio");
    }

    /**
     * 暂停播放（有任务播放时调用）
     */
    public void pause() {
        if (mediaPlayer != null && isPlaying && !isPaused) {
            try {
                mediaPlayer.pause();
                isPaused = true;
                Log.d(TAG, "Paused silence audio");
            } catch (Exception e) {
                Log.e(TAG, "Error pausing MediaPlayer", e);
            }
        }
    }

    /**
     * 恢复播放（任务播放结束后调用）
     */
    public void resume() {
        if (mediaPlayer != null && isPaused) {
            try {
                mediaPlayer.start();
                isPaused = false;
                Log.d(TAG, "Resumed silence audio");
            } catch (Exception e) {
                Log.e(TAG, "Error resuming MediaPlayer", e);
            }
        } else if (!isPlaying) {
            // 如果之前没有播放，则重新开始
            start();
        }
    }

    /**
     * 是否已启动（包括暂停状态）
     */
    public boolean isStarted() {
        return isPlaying;
    }
    
    /**
     * 是否正在实际播放（MediaPlayer 真实状态）
     */
    public boolean isPlaying() {
        if (mediaPlayer != null) {
            try {
                return mediaPlayer.isPlaying();
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    /**
     * 是否处于暂停状态
     */
    public boolean isPaused() {
        return isPaused;
    }
    
    /**
     * 获取详细状态用于调试
     */
    public String getStatusDescription() {
        if (mediaPlayer == null) {
            return "MediaPlayer未创建";
        }
        if (!isPlaying) {
            return "未启动";
        }
        if (isPaused) {
            return "已暂停";
        }
        try {
            if (mediaPlayer.isPlaying()) {
                return "播放中";
            } else {
                return "已停止";
            }
        } catch (Exception e) {
            return "状态异常: " + e.getMessage();
        }
    }

    /**
     * 释放资源
     */
    public void release() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing MediaPlayer", e);
            }
            mediaPlayer = null;
        }
    }
}
