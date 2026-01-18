package com.caleb.scheduledplayer;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

import androidx.work.Configuration;
import androidx.work.WorkManager;

import com.caleb.scheduledplayer.data.database.AppDatabase;
import com.caleb.scheduledplayer.data.repository.TaskLogRepository;
import com.caleb.scheduledplayer.service.scheduler.TaskScheduleManager;
import com.caleb.scheduledplayer.service.worker.TaskCheckWorker;
import com.caleb.scheduledplayer.util.AppLogger;
import com.caleb.scheduledplayer.util.HuaweiDeviceHelper;

/**
 * 应用程序入口类
 * 负责初始化全局配置、数据库、WorkManager等
 */
public class ScheduledPlayerApp extends Application implements Configuration.Provider {

    private static ScheduledPlayerApp instance;
    private AppDatabase database;

    // 通知渠道 ID
    public static final String NOTIFICATION_CHANNEL_PLAYBACK = "playback_channel";

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        
        // 初始化日志系统（最先初始化）
        AppLogger.getInstance().init(this);
        
        // 初始化数据库
        initDatabase();
        
        // 创建通知渠道
        createNotificationChannels();
        
        // 启动定期任务检查（华为设备备份方案）
        initTaskCheckWorker();
        
        // 重新调度所有任务（确保应用启动后任务正常）
        // 注意：这里只在应用进程启动时执行一次，不会因为 Activity 重建而重复执行
        rescheduleAllTasks();
        
        // 清理过期日志
        cleanOldLogs();
    }

    /**
     * 获取应用实例
     */
    public static ScheduledPlayerApp getInstance() {
        return instance;
    }

    /**
     * 获取数据库实例
     */
    public AppDatabase getDatabase() {
        return database;
    }

    /**
     * 初始化 Room 数据库
     */
    private void initDatabase() {
        database = AppDatabase.getInstance(this);
    }

    /**
     * 创建通知渠道 (Android 8.0+)
     */
    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel playbackChannel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_PLAYBACK,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            playbackChannel.setDescription(getString(R.string.notification_channel_description));
            playbackChannel.setShowBadge(false);
            
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(playbackChannel);
            }
        }
    }

    /**
     * WorkManager 配置
     */
    @Override
    public Configuration getWorkManagerConfiguration() {
        return new Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.INFO)
                .build();
    }
    
    /**
     * 初始化定期任务检查（作为 AlarmManager 的备份方案）
     * 主要用于华为等对后台限制严格的设备
     */
    private void initTaskCheckWorker() {
        // 在华为设备上启用定期任务检查
        if (HuaweiDeviceHelper.isHuaweiDevice()) {
            TaskCheckWorker.schedulePeriodicCheck(this);
        }
    }

    /**
     * 重新调度所有启用的任务
     * 应用启动时执行一次，确保闹钟正确设置
     */
    private void rescheduleAllTasks() {
        new Thread(() -> {
            try {
                android.util.Log.d("ScheduledPlayerApp", "Rescheduling all tasks on app start");
                TaskScheduleManager.getInstance(this).rescheduleAllTasks();
            } catch (Exception e) {
                android.util.Log.e("ScheduledPlayerApp", "Error rescheduling tasks", e);
            }
        }).start();
    }

    /**
     * 清理30天前的过期日志
     * 在应用启动时异步执行
     */
    private void cleanOldLogs() {
        new Thread(() -> {
            try {
                TaskLogRepository logRepository = new TaskLogRepository(this);
                int deletedCount = logRepository.cleanOldLogs();
                if (deletedCount > 0) {
                    android.util.Log.d("ScheduledPlayerApp", "Cleaned " + deletedCount + " old logs");
                }
            } catch (Exception e) {
                android.util.Log.e("ScheduledPlayerApp", "Error cleaning old logs", e);
            }
        }).start();
    }
}
