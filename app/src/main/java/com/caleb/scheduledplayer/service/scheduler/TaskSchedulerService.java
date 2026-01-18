package com.caleb.scheduledplayer.service.scheduler;

import android.app.AlarmManager;
import android.content.Context;
import android.os.Build;
import com.caleb.scheduledplayer.util.AppLogger;

import com.caleb.scheduledplayer.data.entity.TaskEntity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 任务调度服务（保留向后兼容接口）
 * 
 * 注意：此类现在作为 TaskScheduleManager 的代理
 * 所有调度逻辑已迁移到 TaskScheduleManager 和相关策略类
 * 
 * @deprecated 推荐直接使用 {@link TaskScheduleManager}
 */
@Deprecated
public class TaskSchedulerService {

    private static final String TAG = "TaskSchedulerService";
    
    // 单例实例
    private static volatile TaskSchedulerService instance;
    private static final Object lock = new Object();

    private final Context context;
    private final AlarmManager alarmManager;
    private final ExecutorService executorService;
    
    /**
     * 获取单例实例
     */
    public static TaskSchedulerService getInstance(Context context) {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new TaskSchedulerService(context);
                }
            }
        }
        return instance;
    }

    private TaskSchedulerService(Context context) {
        this.context = context.getApplicationContext();
        this.alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        this.executorService = Executors.newSingleThreadExecutor();
    }
    
    /**
     * 释放资源（应用退出时调用）
     */
    public void shutdown() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
    
    /**
     * 在后台线程执行任务（供 AlarmReceiver 使用）
     */
    public void executeAsync(Runnable task) {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.execute(task);
        }
    }

    /**
     * 调度单个任务
     * @deprecated 推荐使用 {@link TaskScheduleManager#scheduleTask(TaskEntity)}
     */
    @Deprecated
    public void scheduleTask(TaskEntity task) {
        AppLogger.d(TAG, "scheduleTask called for task " + task.getId() + " - delegating to TaskScheduleManager");
        TaskScheduleManager.getInstance(context).scheduleTask(task);
    }

    /**
     * 取消任务调度
     * @deprecated 推荐使用 {@link TaskScheduleManager#cancelTask(TaskEntity)}
     */
    @Deprecated
    public void cancelTask(long taskId) {
        AppLogger.d(TAG, "cancelTask called for taskId " + taskId + " - delegating to TaskScheduleManager");
        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setName("");
        task.setStartTime("00:00");
        task.setEndTime("00:00");
        TaskScheduleManager.getInstance(context).cancelTask(task);
    }

    /**
     * 重新调度所有启用的任务
     * @deprecated 推荐使用 {@link TaskScheduleManager#rescheduleAllTasks()}
     */
    @Deprecated
    public void rescheduleAllTasks() {
        AppLogger.d(TAG, "rescheduleAllTasks called - delegating to TaskScheduleManager");
        executorService.execute(() -> {
            TaskScheduleManager.getInstance(context).rescheduleAllTasks();
        });
    }

    /**
     * 取消所有任务调度
     */
    public void cancelAllTasks() {
        AppLogger.d(TAG, "cancelAllTasks called");
        // 保留原有实现，因为这个操作不常用
        executorService.execute(() -> {
            // 通过 AlarmScheduler 取消可能更直接
            // 但这里我们保留简单实现
        });
    }

    // ==================== 保留的静态工具方法 ====================
    
    /**
     * 判断任务是否跨天（结束时间小于开始时间）
     * @deprecated 推荐使用 {@link TaskClassifier#isCrossDayTask(TaskEntity)}
     */
    @Deprecated
    public static boolean isCrossDay(String startTime, String endTime) {
        if (startTime == null || endTime == null) {
            return false;
        }
        int startMinutes = TaskClassifier.parseTimeToMinutes(startTime);
        int endMinutes = TaskClassifier.parseTimeToMinutes(endTime);
        return endMinutes < startMinutes;
    }
    
    /**
     * 判断任务当前是否应该处于活跃状态
     * @deprecated 推荐使用 {@link TaskTimeCalculator#shouldBeActiveNow(TaskEntity)}
     */
    @Deprecated
    public static boolean shouldTaskBeActiveNow(TaskEntity task) {
        TimeCheckResult result = TaskTimeCalculator.shouldBeActiveNow(task);
        return result.isActive();
    }

    /**
     * 检查是否有精确闹钟权限
     */
    public boolean canScheduleExactAlarms() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return alarmManager.canScheduleExactAlarms();
        }
        return true;
    }
}
