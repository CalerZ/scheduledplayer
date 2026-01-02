package com.caleb.scheduledplayer.service.worker;

import android.content.Context;
import android.util.Log;

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
import com.caleb.scheduledplayer.service.scheduler.TaskSchedulerService;

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
        Log.d(TAG, "TaskCheckWorker 开始执行");
        
        try {
            Context context = getApplicationContext();
            AppDatabase database = AppDatabase.getInstance(context);
            
            // 获取所有启用的任务
            List<TaskEntity> enabledTasks = database.taskDao().getEnabledTasksSync();
            
            if (enabledTasks.isEmpty()) {
                Log.d(TAG, "没有启用的任务");
                return Result.success();
            }
            
            // 获取当前时间
            Calendar now = Calendar.getInstance();
            int currentHour = now.get(Calendar.HOUR_OF_DAY);
            int currentMinute = now.get(Calendar.MINUTE);
            int currentTimeMinutes = currentHour * 60 + currentMinute;
            int currentDayOfWeek = now.get(Calendar.DAY_OF_WEEK);
            
            for (TaskEntity task : enabledTasks) {
                // 检查是否应该在今天执行
                if (!shouldExecuteToday(task, currentDayOfWeek)) {
                    continue;
                }
                
                // 解析任务时间
                int startTimeMinutes = parseTimeToMinutes(task.getStartTime());
                int endTimeMinutes = parseTimeToMinutes(task.getEndTime());
                
                // 检查是否在执行时间范围内
                boolean shouldPlay = false;
                
                if (startTimeMinutes <= endTimeMinutes) {
                    // 同一天内的任务
                    shouldPlay = currentTimeMinutes >= startTimeMinutes && currentTimeMinutes < endTimeMinutes;
                } else {
                    // 跨天任务
                    shouldPlay = currentTimeMinutes >= startTimeMinutes || currentTimeMinutes < endTimeMinutes;
                }
                
                if (shouldPlay) {
                    Log.d(TAG, "任务 " + task.getId() + " 应该正在播放，检查并启动");
                    // 启动任务播放
                    AudioPlaybackService.startTaskPlayback(context, task.getId());
                }
            }
            
            // 重新调度所有任务的闹钟（以防闹钟被系统取消）
            TaskSchedulerService schedulerService = new TaskSchedulerService(context);
            schedulerService.rescheduleAllTasks();
            
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "TaskCheckWorker 执行失败", e);
            return Result.retry();
        }
    }
    
    /**
     * 检查任务是否应该在指定的星期几执行
     */
    private boolean shouldExecuteToday(TaskEntity task, int dayOfWeek) {
        int repeatDays = task.getRepeatDays();
        if (repeatDays == 0) {
            return true; // 没有设置重复日期，每天都执行
        }
        
        // 将 Calendar 的星期转换为 TaskEntity 的位标志
        int dayFlag = getDayFlag(dayOfWeek);
        return (repeatDays & dayFlag) != 0;
    }
    
    /**
     * 将 Calendar 的星期转换为 TaskEntity 的星期标志
     */
    private int getDayFlag(int calendarDayOfWeek) {
        switch (calendarDayOfWeek) {
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
    
    /**
     * 解析时间字符串为分钟数
     */
    private int parseTimeToMinutes(String time) {
        if (time == null || time.isEmpty()) {
            return 0;
        }
        try {
            String[] parts = time.split(":");
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            return hour * 60 + minute;
        } catch (Exception e) {
            return 0;
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
        
        Log.d(TAG, "已调度定期任务检查");
    }
    
    /**
     * 取消定期任务检查
     */
    public static void cancelPeriodicCheck(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
        Log.d(TAG, "已取消定期任务检查");
    }
}
