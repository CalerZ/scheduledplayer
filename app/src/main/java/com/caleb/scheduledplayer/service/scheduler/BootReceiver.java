package com.caleb.scheduledplayer.service.scheduler;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.caleb.scheduledplayer.util.AppLogger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 开机广播接收器
 * 设备启动后重新调度所有任务
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";
    
    // 使用单独的线程执行数据库操作，避免ANR
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        String action = intent.getAction();
        
        // 支持多种启动广播
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
            "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            
            AppLogger.d(TAG, "Boot completed, rescheduling tasks via TaskScheduleManager");
            
            // 获取 PendingResult 以便在异步完成后通知系统
            final PendingResult pendingResult = goAsync();
            final Context appContext = context.getApplicationContext();
            
            // 在后台线程执行数据库操作，避免ANR
            executor.execute(() -> {
                try {
                    TaskScheduleManager.getInstance(appContext).rescheduleAllTasks();
                    AppLogger.d(TAG, "Tasks rescheduled successfully after boot");
                } catch (Exception e) {
                    AppLogger.e(TAG, "Error rescheduling tasks after boot", e);
                } finally {
                    // 通知系统广播处理完成
                    pendingResult.finish();
                }
            });
        }
    }
}
