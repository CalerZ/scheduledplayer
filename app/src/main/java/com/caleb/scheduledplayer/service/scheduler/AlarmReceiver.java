package com.caleb.scheduledplayer.service.scheduler;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

import com.caleb.scheduledplayer.util.AppLogger;

/**
 * 闹钟广播接收器
 * 接收 AlarmManager 的定时广播，触发任务开始/结束
 */
public class AlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "AlarmReceiver";

    public static final String ACTION_TASK_START = "com.caleb.scheduledplayer.TASK_START";
    public static final String ACTION_TASK_STOP = "com.caleb.scheduledplayer.TASK_STOP";
    public static final String ACTION_TASK_RETRY = "com.caleb.scheduledplayer.TASK_RETRY";
    public static final String EXTRA_TASK_ID = "task_id";

    @Override
    public void onReceive(Context context, Intent intent) {
        long receiveTime = System.currentTimeMillis();
        AppLogger.getInstance().d(TAG, "========== AlarmReceiver.onReceive() at " + new java.util.Date(receiveTime) + " ==========");
        
        if (intent == null || intent.getAction() == null) {
            AppLogger.getInstance().w(TAG, "Intent or action is null");
            return;
        }

        String action = intent.getAction();
        long taskId = intent.getLongExtra(EXTRA_TASK_ID, -1);

        if (taskId == -1) {
            AppLogger.getInstance().w(TAG, "Invalid task ID");
            return;
        }

        AppLogger.getInstance().d(TAG, "Received alarm: action=" + action + ", taskId=" + taskId + ", time=" + new java.util.Date(receiveTime));

        // 获取 WakeLock 确保 CPU 不休眠
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ScheduledPlayer:AlarmReceiverWakeLock"
        );
        wakeLock.acquire(60 * 1000L); // 最多持有 60 秒
        AppLogger.getInstance().d(TAG, "WakeLock acquired");

        // 使用 goAsync() 延长 BroadcastReceiver 生命周期
        PendingResult pendingResult = goAsync();

        switch (action) {
            case ACTION_TASK_START:
                handleTaskStart(context, taskId, wakeLock, pendingResult);
                break;
            case ACTION_TASK_STOP:
                handleTaskStop(context, taskId, wakeLock, pendingResult);
                break;
            case ACTION_TASK_RETRY:
                handleTaskRetry(context, taskId, wakeLock, pendingResult);
                break;
            default:
                AppLogger.getInstance().w(TAG, "Unknown action: " + action);
                releaseWakeLockAndFinish(wakeLock, pendingResult);
                break;
        }
    }

    private void handleTaskStart(Context context, long taskId, PowerManager.WakeLock wakeLock, PendingResult pendingResult) {
        AppLogger.getInstance().d(TAG, "handleTaskStart() for taskId=" + taskId);
        // 使用 TaskSchedulerService 的单例线程池，避免内存泄漏
        TaskSchedulerService.getInstance(context).executeAsync(() -> {
            try {
                AppLogger.getInstance().d(TAG, "Executor started for task " + taskId);
                
                // 委托给新的 TaskScheduleManager 处理
                TaskScheduleManager.getInstance(context).handleStartAlarm(taskId);
                
            } catch (Exception e) {
                AppLogger.getInstance().e(TAG, "Error handling task start for taskId=" + taskId, e);
            } finally {
                AppLogger.getInstance().d(TAG, "handleTaskStart() completed for taskId=" + taskId);
                releaseWakeLockAndFinish(wakeLock, pendingResult);
            }
        });
    }

    private void handleTaskStop(Context context, long taskId, PowerManager.WakeLock wakeLock, PendingResult pendingResult) {
        // 使用异步执行，确保数据库操作不阻塞主线程
        TaskSchedulerService.getInstance(context).executeAsync(() -> {
            try {
                AppLogger.getInstance().d(TAG, ">>> Stopping playback for task " + taskId + " <<<");
                
                // 委托给新的 TaskScheduleManager 处理
                TaskScheduleManager.getInstance(context).handleStopAlarm(taskId);
                
            } catch (Exception e) {
                AppLogger.getInstance().e(TAG, "Error handling task stop for taskId=" + taskId, e);
            } finally {
                releaseWakeLockAndFinish(wakeLock, pendingResult);
            }
        });
    }

    private void handleTaskRetry(Context context, long taskId, PowerManager.WakeLock wakeLock, PendingResult pendingResult) {
        AppLogger.getInstance().d(TAG, "handleTaskRetry() for taskId=" + taskId);
        TaskSchedulerService.getInstance(context).executeAsync(() -> {
            try {
                AppLogger.getInstance().d(TAG, "Executor started for retry task " + taskId);
                
                // 委托给 TaskScheduleManager 处理重试
                TaskScheduleManager.getInstance(context).handleRetryAlarm(taskId);
                
            } catch (Exception e) {
                AppLogger.getInstance().e(TAG, "Error handling task retry for taskId=" + taskId, e);
            } finally {
                AppLogger.getInstance().d(TAG, "handleTaskRetry() completed for taskId=" + taskId);
                releaseWakeLockAndFinish(wakeLock, pendingResult);
            }
        });
    }

    private void releaseWakeLockAndFinish(PowerManager.WakeLock wakeLock, PendingResult pendingResult) {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            AppLogger.getInstance().d(TAG, "WakeLock released");
        }
        if (pendingResult != null) {
            pendingResult.finish();
            AppLogger.getInstance().d(TAG, "PendingResult finished");
        }
    }

}
