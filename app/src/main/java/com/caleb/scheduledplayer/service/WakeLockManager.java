package com.caleb.scheduledplayer.service;

import android.content.Context;
import android.os.PowerManager;
import android.util.Log;

/**
 * 唤醒锁管理器
 * 确保在熄屏状态下音频能够正常播放
 */
public class WakeLockManager {
    
    private static final String TAG = "WakeLockManager";
    private static final String WAKE_LOCK_TAG = "ScheduledPlayer:PlaybackWakeLock";
    
    private static WakeLockManager instance;
    
    private final PowerManager powerManager;
    private PowerManager.WakeLock wakeLock;
    private int acquireCount;
    
    private WakeLockManager(Context context) {
        this.powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        this.acquireCount = 0;
    }
    
    /**
     * 获取单例实例
     */
    public static synchronized WakeLockManager getInstance(Context context) {
        if (instance == null) {
            instance = new WakeLockManager(context.getApplicationContext());
        }
        return instance;
    }
    
    /**
     * 获取唤醒锁
     * 使用引用计数，支持多次获取
     */
    public synchronized void acquire() {
        acquireCount++;
        
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    WAKE_LOCK_TAG
            );
            wakeLock.setReferenceCounted(false);
        }
        
        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
            Log.d(TAG, "唤醒锁已获取，计数: " + acquireCount);
        }
    }
    
    /**
     * 获取带超时的唤醒锁
     */
    public synchronized void acquire(long timeout) {
        acquireCount++;
        
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    WAKE_LOCK_TAG
            );
            wakeLock.setReferenceCounted(false);
        }
        
        if (!wakeLock.isHeld()) {
            wakeLock.acquire(timeout);
            Log.d(TAG, "唤醒锁已获取（超时: " + timeout + "ms），计数: " + acquireCount);
        }
    }
    
    /**
     * 释放唤醒锁
     */
    public synchronized void release() {
        if (acquireCount > 0) {
            acquireCount--;
        }
        
        if (acquireCount == 0 && wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
                Log.d(TAG, "唤醒锁已释放");
            } catch (Exception e) {
                Log.w(TAG, "释放唤醒锁时出错: " + e.getMessage());
            }
        }
    }
    
    /**
     * 强制释放唤醒锁
     */
    public synchronized void forceRelease() {
        acquireCount = 0;
        
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
                Log.d(TAG, "唤醒锁已强制释放");
            } catch (Exception e) {
                Log.w(TAG, "强制释放唤醒锁时出错: " + e.getMessage());
            }
        }
    }
    
    /**
     * 检查唤醒锁是否被持有
     */
    public synchronized boolean isHeld() {
        return wakeLock != null && wakeLock.isHeld();
    }
    
    /**
     * 获取当前引用计数
     */
    public synchronized int getAcquireCount() {
        return acquireCount;
    }
    
    /**
     * 检查屏幕是否亮着
     */
    public boolean isScreenOn() {
        return powerManager.isInteractive();
    }
}
