package com.caleb.scheduledplayer.service.worker;

import android.content.Context;
import com.caleb.scheduledplayer.util.AppLogger;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.caleb.scheduledplayer.data.database.AppDatabase;
import com.caleb.scheduledplayer.data.entity.TaskEntity;
import com.caleb.scheduledplayer.service.player.AudioPlaybackService;
import com.caleb.scheduledplayer.service.scheduler.TaskScheduleManager;
import com.caleb.scheduledplayer.service.scheduler.TaskTimeCalculator;
import com.caleb.scheduledplayer.service.scheduler.TaskType;
import com.caleb.scheduledplayer.service.scheduler.TaskClassifier;
import com.caleb.scheduledplayer.service.scheduler.TaskExecutionState;
import com.caleb.scheduledplayer.service.scheduler.TimeCheckResult;

import java.util.Calendar;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 任务检查 Worker
 * 作为 AlarmManager 的备份方案，定期检查是否有任务需要执行
 * 主要用于华为等对后台限制严格的设备
 */
public class TaskCheckWorker extends Worker {
    
    private static final String TAG = "TaskCheckWorker";
    private static final String WORK_NAME = "task_check_worker";
    
    public TaskCheckWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }
    
    @NonNull
    @Override
    public Result doWork() {
        AppLogger.d(TAG, "TaskCheckWorker 开始执行");
        
        try {
            Context context = getApplicationContext();
            AppDatabase database = AppDatabase.getInstance(context);
            
            // 获取所有启用的任务
            List<TaskEntity> enabledTasks = database.taskDao().getEnabledTasksSync();
            
            if (enabledTasks.isEmpty()) {
                AppLogger.d(TAG, "没有启用的任务");
                return Result.success();
            }
            
            // 获取当前时间（仅用于日志）
            Calendar now = Calendar.getInstance();
            AppLogger.d(TAG, "当前时间: " + now.getTime());
            
            for (TaskEntity task : enabledTasks) {
                // 使用新的时间计算器检查任务是否应该活跃
                TimeCheckResult checkResult = TaskTimeCalculator.shouldBeActiveNow(task);
                
                if (checkResult.isActive()) {
                    // 只有在任务没有正在播放时才启动（避免重复启动导致重新播放）
                    TaskExecutionState state = task.getExecutionStateEnum();
                    if (state != TaskExecutionState.EXECUTING && state != TaskExecutionState.PAUSED) {
                        AppLogger.d(TAG, "任务 " + task.getId() + " 应该正在播放但状态为 " + state + "，启动播放");
                        AudioPlaybackService.startTaskPlayback(context, task.getId());
                    } else {
                        AppLogger.d(TAG, "任务 " + task.getId() + " 已经在播放中，跳过");
                    }
                } else {
                    // 任务不应活跃
                    AppLogger.d(TAG, "任务 " + task.getId() + " 不应该播放，原因: " + checkResult.getReason());
                    
                    // 只有全天播放模式需要在这里停止
                    TaskType type = TaskClassifier.classify(task);
                    if (type.isAllDay()) {
                        AppLogger.d(TAG, "全天播放任务 " + task.getId() + " 不应该播放，检查并停止");
                        AudioPlaybackService.stopTaskPlayback(context, task.getId());
                        
                        // 如果是一次性全天播放任务，禁用它
                        if (task.isOneTime()) {
                            AppLogger.d(TAG, "一次性全天播放任务 " + task.getId() + " 完成，禁用它");
                            database.taskDao().updateEnabled(task.getId(), false, System.currentTimeMillis());
                        }
                    }
                    // 非全天播放任务不在这里停止，让结束闹钟处理
                }
            }
            
            // 注意：不再调用 rescheduleAllTasks()
            // 因为 rescheduleAllTasks() 会调用 handleReboot()，对正在播放的任务会重新调用 startPlayback
            // 这会导致不必要的播放重启
            // 闹钟调度应该由正常的任务生命周期（保存/启用/禁用/完成）来管理
            // 这里只负责作为备份机制确保任务能启动
            
            return Result.success();
        } catch (Exception e) {
            AppLogger.e(TAG, "TaskCheckWorker 执行失败", e);
            return Result.retry();
        }
    }
    
    /**
     * 启动定期任务检查
     * 每 15 分钟检查一次
     */
    public static void schedulePeriodicCheck(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiresBatteryNotLow(false)  // 即使电量低也执行
                .build();
        
        PeriodicWorkRequest workRequest = new PeriodicWorkRequest.Builder(
                TaskCheckWorker.class,
                15, TimeUnit.MINUTES  // 最小间隔 15 分钟
        )
                .setConstraints(constraints)
                .build();
        
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,  // 如果已存在，保留原有的
                workRequest
        );
        
        AppLogger.d(TAG, "已调度定期任务检查");
    }
    
    /**
     * 取消定期任务检查
     */
    public static void cancelPeriodicCheck(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
        AppLogger.d(TAG, "已取消定期任务检查");
    }
}
