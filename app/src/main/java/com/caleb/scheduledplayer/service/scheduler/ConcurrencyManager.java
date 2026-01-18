package com.caleb.scheduledplayer.service.scheduler;

import com.caleb.scheduledplayer.util.AppLogger;

import com.caleb.scheduledplayer.data.dao.TaskDao;

/**
 * 并发播放控制管理器
 * 用于限制同时播放的任务数量
 */
public class ConcurrencyManager {

    private static final String TAG = "ConcurrencyManager";

    /**
     * 最大并发播放任务数
     */
    public static final int MAX_CONCURRENT_PLAYBACK = 10;

    private final TaskDao taskDao;

    public ConcurrencyManager(TaskDao taskDao) {
        this.taskDao = taskDao;
    }

    /**
     * 检查是否可以启动新的播放任务
     * @return true 如果当前执行中任务数 < MAX_CONCURRENT_PLAYBACK
     */
    public boolean canStartPlayback() {
        int currentCount = getCurrentPlaybackCount();
        boolean canStart = currentCount < MAX_CONCURRENT_PLAYBACK;
        AppLogger.d(TAG, "canStartPlayback: currentCount=" + currentCount + 
                ", max=" + MAX_CONCURRENT_PLAYBACK + ", canStart=" + canStart);
        return canStart;
    }

    /**
     * 获取当前执行中任务数（基于数据库 execution_state = EXECUTING）
     */
    public int getCurrentPlaybackCount() {
        return taskDao.getExecutingTaskCount();
    }

    /**
     * 获取剩余可用播放槽位数
     */
    public int getAvailableSlots() {
        return Math.max(0, MAX_CONCURRENT_PLAYBACK - getCurrentPlaybackCount());
    }

    /**
     * 检查是否已达到并发上限
     */
    public boolean isAtCapacity() {
        return getCurrentPlaybackCount() >= MAX_CONCURRENT_PLAYBACK;
    }
}
