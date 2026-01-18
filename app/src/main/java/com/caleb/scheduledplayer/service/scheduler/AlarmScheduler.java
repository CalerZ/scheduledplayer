package com.caleb.scheduledplayer.service.scheduler;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import com.caleb.scheduledplayer.util.AppLogger;

import java.util.Date;

/**
 * 闹钟调度器
 * 封装 AlarmManager 的闹钟设置和取消逻辑
 */
public class AlarmScheduler {

    private static final String TAG = "AlarmScheduler";

    /**
     * 重试闹钟间隔：5分钟
     */
    public static final long RETRY_INTERVAL_MS = 5 * 60 * 1000L;

    private final Context context;
    private final AlarmManager alarmManager;

    public AlarmScheduler(Context context) {
        this.context = context.getApplicationContext();
        this.alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    }

    /**
     * 设置开始闹钟
     * 使用 AlarmClockInfo 确保在 Doze 模式下也能触发（最高优先级）
     * 
     * @param taskId 任务ID
     * @param triggerTime 触发时间戳
     */
    public void setStartAlarm(long taskId, long triggerTime) {
        if (triggerTime <= System.currentTimeMillis()) {
            AppLogger.w(TAG, "Start alarm time has passed for task " + taskId + ", skipping");
            return;
        }

        PendingIntent pi = createPendingIntent(taskId, true);

        // 使用 AlarmClockInfo 确保 Doze 模式下也能触发
        AlarmManager.AlarmClockInfo alarmClockInfo = new AlarmManager.AlarmClockInfo(triggerTime, pi);
        alarmManager.setAlarmClock(alarmClockInfo, pi);

        AppLogger.d(TAG, "Set start alarm for task " + taskId + " at " + new Date(triggerTime)
                + " (in " + ((triggerTime - System.currentTimeMillis()) / 1000) + " seconds)");
    }

    /**
     * 设置结束闹钟
     * 使用精确闹钟，优先级低于开始闹钟
     * 
     * @param taskId 任务ID
     * @param triggerTime 触发时间戳
     */
    public void setEndAlarm(long taskId, long triggerTime) {
        if (triggerTime <= System.currentTimeMillis()) {
            AppLogger.w(TAG, "End alarm time has passed for task " + taskId + ", skipping");
            return;
        }

        PendingIntent pi = createPendingIntent(taskId, false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ 需要检查精确闹钟权限
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pi);
            } else {
                // 没有精确闹钟权限，使用非精确闹钟
                AppLogger.w(TAG, "Cannot schedule exact alarms, using inexact alarm for task " + taskId);
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pi);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6.0+ 使用 setExactAndAllowWhileIdle
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pi);
        } else {
            // 低版本使用 setExact
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pi);
        }

        AppLogger.d(TAG, "Set end alarm for task " + taskId + " at " + new Date(triggerTime)
                + " (in " + ((triggerTime - System.currentTimeMillis()) / 1000) + " seconds)");
    }

    /**
     * 设置午夜检查闹钟（用于全天播放任务）
     * 
     * @param taskId 任务ID
     * @param triggerTime 触发时间戳
     */
    public void setMidnightCheckAlarm(long taskId, long triggerTime) {
        // 午夜检查使用开始闹钟的优先级
        setStartAlarm(taskId, triggerTime);
    }

    /**
     * 取消任务的所有闹钟（开始、结束和重试）
     * 
     * @param taskId 任务ID
     */
    public void cancelAlarms(long taskId) {
        cancelStartAlarm(taskId);
        cancelEndAlarm(taskId);
        cancelRetryAlarm(taskId);
        AppLogger.d(TAG, "Cancelled all alarms for task " + taskId);
    }

    /**
     * 只取消开始闹钟
     * 
     * @param taskId 任务ID
     */
    public void cancelStartAlarm(long taskId) {
        PendingIntent pi = createPendingIntent(taskId, true);
        alarmManager.cancel(pi);
        AppLogger.d(TAG, "Cancelled start alarm for task " + taskId);
    }

    /**
     * 只取消结束闹钟
     * 
     * @param taskId 任务ID
     */
    public void cancelEndAlarm(long taskId) {
        PendingIntent pi = createPendingIntent(taskId, false);
        alarmManager.cancel(pi);
        AppLogger.d(TAG, "Cancelled end alarm for task " + taskId);
    }

    /**
     * 创建闹钟的 PendingIntent
     * 
     * @param taskId 任务ID
     * @param isStart 是否为开始闹钟
     * @return PendingIntent
     */
    private PendingIntent createPendingIntent(long taskId, boolean isStart) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.setAction(isStart ? AlarmReceiver.ACTION_TASK_START : AlarmReceiver.ACTION_TASK_STOP);
        intent.putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId);

        int requestCode = calculateRequestCode(taskId, isStart);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        return PendingIntent.getBroadcast(context, requestCode, intent, flags);
    }

    /**
     * 计算 Request Code
     * 直接使用 taskId 作为 suffix，避免哈希冲突
     * 
     * Room 自增 ID 从 1 开始，正常不会超过 Integer.MAX_VALUE
     * 开始闹钟: taskId * 3 (mod 3 = 0)
     * 结束闹钟: taskId * 3 + 1 (mod 3 = 1)
     * 重试闹钟: taskId * 3 + 2 (mod 3 = 2)
     * 
     * @param taskId 任务ID
     * @param isStart 是否为开始闹钟
     * @return Request Code
     */
    private int calculateRequestCode(long taskId, boolean isStart) {
        // 取模 700000000 确保乘以 3 后不会溢出
        // Integer.MAX_VALUE = 2147483647，除以 3 约为 7 亿
        int baseCode = (int) (taskId % 700000000) * 3;
        return isStart ? baseCode : (baseCode + 1);
    }

    /**
     * 检查是否有精确闹钟权限
     * @return 是否有权限
     */
    public boolean canScheduleExactAlarms() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return alarmManager.canScheduleExactAlarms();
        }
        return true;
    }

    // ==================== 重试闹钟（用于并发限制等待） ====================

    /**
     * 设置重试启动闹钟
     * 用于并发限制时，5分钟后重试启动任务
     * 
     * @param taskId 任务ID
     */
    public void setRetryAlarm(long taskId) {
        long triggerTime = System.currentTimeMillis() + RETRY_INTERVAL_MS;

        PendingIntent pi = createRetryPendingIntent(taskId);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pi);
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pi);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pi);
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pi);
        }

        AppLogger.d(TAG, "Set retry alarm for task " + taskId + " at " + new Date(triggerTime)
                + " (in " + (RETRY_INTERVAL_MS / 1000) + " seconds)");
    }

    /**
     * 取消重试闹钟
     * 
     * @param taskId 任务ID
     */
    public void cancelRetryAlarm(long taskId) {
        PendingIntent pi = createRetryPendingIntent(taskId);
        alarmManager.cancel(pi);
        AppLogger.d(TAG, "Cancelled retry alarm for task " + taskId);
    }

    /**
     * 创建重试闹钟的 PendingIntent
     * 
     * @param taskId 任务ID
     * @return PendingIntent
     */
    private PendingIntent createRetryPendingIntent(long taskId) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.setAction(AlarmReceiver.ACTION_TASK_RETRY);
        intent.putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId);

        int requestCode = calculateRetryRequestCode(taskId);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        return PendingIntent.getBroadcast(context, requestCode, intent, flags);
    }

    /**
     * 计算重试闹钟的 Request Code
     * 使用不同的基数避免与开始/结束闹钟冲突
     * 开始闹钟: taskId * 3 (mod 3 = 0)
     * 结束闹钟: taskId * 3 + 1 (mod 3 = 1)
     * 重试闹钟: taskId * 3 + 2 (mod 3 = 2)
     * 
     * @param taskId 任务ID
     * @return Request Code
     */
    private int calculateRetryRequestCode(long taskId) {
        // 取模 700000000 确保乘以 3 后不会溢出
        return (int) (taskId % 700000000) * 3 + 2;
    }
}
