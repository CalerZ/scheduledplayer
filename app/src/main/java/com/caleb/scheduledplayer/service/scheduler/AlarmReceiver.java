package com.caleb.scheduledplayer.service.scheduler;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

import com.caleb.scheduledplayer.data.database.AppDatabase;
import com.caleb.scheduledplayer.data.entity.TaskEntity;
import com.caleb.scheduledplayer.service.player.AudioPlaybackService;

import java.util.Calendar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 闹钟广播接收器
 * 接收 AlarmManager 的定时广播，触发任务开始/结束
 */
public class AlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "AlarmReceiver";

    public static final String ACTION_TASK_START = "com.caleb.scheduledplayer.TASK_START";
    public static final String ACTION_TASK_STOP = "com.caleb.scheduledplayer.TASK_STOP";
    public static final String EXTRA_TASK_ID = "task_id";

    private static final ExecutorService executorService = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(Context context, Intent intent) {
        long receiveTime = System.currentTimeMillis();
        Log.d(TAG, "========== AlarmReceiver.onReceive() at " + new java.util.Date(receiveTime) + " ==========");
        
        if (intent == null || intent.getAction() == null) {
            Log.w(TAG, "Intent or action is null");
            return;
        }

        String action = intent.getAction();
        long taskId = intent.getLongExtra(EXTRA_TASK_ID, -1);

        if (taskId == -1) {
            Log.w(TAG, "Invalid task ID");
            return;
        }

        Log.d(TAG, "Received alarm: action=" + action + ", taskId=" + taskId + ", time=" + new java.util.Date(receiveTime));

        // 获取 WakeLock 确保 CPU 不休眠
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ScheduledPlayer:AlarmReceiverWakeLock"
        );
        wakeLock.acquire(60 * 1000L); // 最多持有 60 秒
        Log.d(TAG, "WakeLock acquired");

        // 使用 goAsync() 延长 BroadcastReceiver 生命周期
        PendingResult pendingResult = goAsync();

        switch (action) {
            case ACTION_TASK_START:
                handleTaskStart(context, taskId, wakeLock, pendingResult);
                break;
            case ACTION_TASK_STOP:
                handleTaskStop(context, taskId, wakeLock, pendingResult);
                break;
            default:
                Log.w(TAG, "Unknown action: " + action);
                releaseWakeLockAndFinish(wakeLock, pendingResult);
                break;
        }
    }

    private void handleTaskStart(Context context, long taskId, PowerManager.WakeLock wakeLock, PendingResult pendingResult) {
        Log.d(TAG, "handleTaskStart() for taskId=" + taskId);
        executorService.execute(() -> {
            try {
                Log.d(TAG, "Executor started for task " + taskId);
                TaskEntity task = AppDatabase.getInstance(context)
                        .taskDao()
                        .getTaskByIdSync(taskId);

                if (task == null || !task.isEnabled()) {
                    Log.d(TAG, "Task " + taskId + " is null or disabled, skipping. task=" + task);
                    return;
                }

                // 检查今天是否需要执行
                if (task.getRepeatDays() != 0) {
                    int todayFlag = getTodayFlag();
                    if ((task.getRepeatDays() & todayFlag) == 0) {
                        Log.d(TAG, "Task " + taskId + " not scheduled for today (repeatDays=" + task.getRepeatDays() + ", todayFlag=" + todayFlag + "), skipping");
                        // 重新调度到下一个执行日
                        new TaskSchedulerService(context).scheduleTask(task);
                        return;
                    }
                }

                // 启动播放
                Log.d(TAG, ">>> Starting playback for task " + taskId + " <<<");
                AudioPlaybackService.startTaskPlayback(context, taskId);
                Log.d(TAG, ">>> startTaskPlayback() called for task " + taskId + " <<<");

                // 重新调度下一次执行（如果是重复任务）
                if (task.getRepeatDays() != 0) {
                    Log.d(TAG, "Rescheduling repeat task " + taskId);
                    new TaskSchedulerService(context).scheduleTask(task);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling task start for taskId=" + taskId, e);
            } finally {
                Log.d(TAG, "handleTaskStart() completed for taskId=" + taskId);
                releaseWakeLockAndFinish(wakeLock, pendingResult);
            }
        });
    }

    private void handleTaskStop(Context context, long taskId, PowerManager.WakeLock wakeLock, PendingResult pendingResult) {
        try {
            Log.d(TAG, ">>> Stopping playback for task " + taskId + " <<<");
            AudioPlaybackService.stopTaskPlayback(context, taskId);
            Log.d(TAG, ">>> stopTaskPlayback() called for task " + taskId + " <<<");
        } catch (Exception e) {
            Log.e(TAG, "Error handling task stop for taskId=" + taskId, e);
        } finally {
            releaseWakeLockAndFinish(wakeLock, pendingResult);
        }
    }

    private void releaseWakeLockAndFinish(PowerManager.WakeLock wakeLock, PendingResult pendingResult) {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "WakeLock released");
        }
        if (pendingResult != null) {
            pendingResult.finish();
            Log.d(TAG, "PendingResult finished");
        }
    }

    /**
     * 获取今天的星期标志
     */
    private int getTodayFlag() {
        int dayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
        switch (dayOfWeek) {
            case Calendar.MONDAY:
                return TaskEntity.MONDAY;
            case Calendar.TUESDAY:
                return TaskEntity.TUESDAY;
            case Calendar.WEDNESDAY:
                return TaskEntity.WEDNESDAY;
            case Calendar.THURSDAY:
                return TaskEntity.THURSDAY;
            case Calendar.FRIDAY:
                return TaskEntity.FRIDAY;
            case Calendar.SATURDAY:
                return TaskEntity.SATURDAY;
            case Calendar.SUNDAY:
                return TaskEntity.SUNDAY;
            default:
                return 0;
        }
    }
}
