package com.caleb.scheduledplayer.service.scheduler.strategy;

import com.caleb.scheduledplayer.data.entity.TaskEntity;
import com.caleb.scheduledplayer.service.player.AudioPlaybackService;
import com.caleb.scheduledplayer.service.scheduler.BaseScheduleStrategy;
import com.caleb.scheduledplayer.service.scheduler.ScheduleResult;
import com.caleb.scheduledplayer.service.scheduler.TaskScheduleManager;
import com.caleb.scheduledplayer.service.scheduler.TaskTimeCalculator;
import com.caleb.scheduledplayer.service.scheduler.TaskType;
import com.caleb.scheduledplayer.service.scheduler.TimeCheckResult;

/**
 * 一次性非跨天任务调度策略
 * 例如：今天 9:00-12:00 播放一次
 */
public class OneTimeNormalStrategy extends BaseScheduleStrategy {

    @Override
    public TaskType getSupportedType() {
        return TaskType.ONE_TIME_NORMAL;
    }

    @Override
    public ScheduleResult schedule(TaskEntity task, TaskScheduleManager manager) {
        TimeCheckResult checkResult = TaskTimeCalculator.shouldBeActiveNow(task);

        if (checkResult.isActive()) {
            long endTime = checkResult.getEffectiveEndTime();
            
            // 检查任务是否已经在播放，避免重复启动导致从头播放
            if (AudioPlaybackService.isTaskCurrentlyPlaying(task.getId())) {
                logSchedule(task, "Task already playing, skipping start, just updating end alarm");
                manager.setEndAlarm(task.getId(), endTime);
                return ScheduleResult.immediate(endTime);
            }
            
            // 当前在时间范围内，立即开始播放
            logSchedule(task, "Currently in time range, starting immediately");
            
            long now = System.currentTimeMillis();
            
            startPlaybackAndUpdateState(task, manager, now, endTime);
            manager.setEndAlarm(task.getId(), endTime);
            
            return ScheduleResult.immediate(endTime);
        }

        // 不在时间范围内
        switch (checkResult.getReason()) {
            case NOT_IN_RANGE:
                // 计算开始时间
                long startTime = TaskTimeCalculator.calculateNextStartTime(task);
                if (startTime < 0) {
                    // 时间已过，一次性任务没有下次执行
                    logSchedule(task, "Start time has passed, no next execution");
                    manager.disableTask(task);
                    return ScheduleResult.noSchedule("Start time has passed");
                }
                
                long endTime = TaskTimeCalculator.calculateEndTimeForStart(task, startTime);
                
                logSchedule(task, "Scheduling start at " + new java.util.Date(startTime));
                manager.setStartAlarm(task.getId(), startTime);
                manager.setEndAlarm(task.getId(), endTime);
                
                return ScheduleResult.scheduled(startTime, endTime);

            case NOT_REPEAT_DAY:
                // 一次性任务不应该出现这个原因
                logWarning(task, "Unexpected NOT_REPEAT_DAY for one-time task");
                return ScheduleResult.noSchedule("Unexpected state");

            default:
                logSchedule(task, "Not scheduling, reason: " + checkResult.getReason());
                return ScheduleResult.noSchedule(checkResult.getReason().getDescription());
        }
    }

    @Override
    public void handleStart(TaskEntity task, TaskScheduleManager manager) {
        if (!validateTaskForStart(task, manager)) {
            return;
        }

        // 二次验证时间
        TimeCheckResult checkResult = TaskTimeCalculator.shouldBeActiveNow(task);
        if (!checkResult.isActive()) {
            logWarning(task, "Start alarm triggered but not in time range: " 
                    + checkResult.getReason());
            // 不重新调度，因为是一次性任务
            return;
        }

        long now = System.currentTimeMillis();
        long endTime = checkResult.getEffectiveEndTime();

        logSchedule(task, "Start alarm triggered, starting playback");
        startPlaybackAndUpdateState(task, manager, now, endTime);
        
        // 确保结束闹钟已设置（可能在 schedule 时已设置）
        manager.setEndAlarm(task.getId(), endTime);
    }

    @Override
    public void handleStop(TaskEntity task, TaskScheduleManager manager) {
        logSchedule(task, "Stop alarm triggered");
        
        stopPlaybackAndUpdateState(task, manager);
        
        // 一次性任务完成后禁用
        disableOneTimeTask(task, manager);
    }

    @Override
    protected void handleRebootInActiveRange(TaskEntity task, TaskScheduleManager manager,
            TimeCheckResult checkResult) {
        // 检查任务是否已经在播放
        if (AudioPlaybackService.isTaskCurrentlyPlaying(task.getId())) {
            logSchedule(task, "Task already playing, skipping start");
            manager.setEndAlarm(task.getId(), checkResult.getEffectiveEndTime());
            return;
        }
        
        // 一次性任务重启后在时间范围内
        // 检查执行状态决定是否恢复
        switch (task.getExecutionStateEnum()) {
            case EXECUTING:
            case PAUSED:
            case SCHEDULED:
                // 之前已调度或在执行，现在开始执行
                logSchedule(task, "Starting playback after reboot");
                startPlaybackAndUpdateState(task, manager,
                        System.currentTimeMillis(), checkResult.getEffectiveEndTime());
                manager.setEndAlarm(task.getId(), checkResult.getEffectiveEndTime());
                break;

            case COMPLETED:
            case DISABLED:
                // 已完成或已禁用，不恢复
                logSchedule(task, "Already completed or disabled, not resuming");
                break;

            default:
                // IDLE 状态，可能是新任务，开始执行
                logSchedule(task, "IDLE state, starting playback");
                startPlaybackAndUpdateState(task, manager,
                        System.currentTimeMillis(), checkResult.getEffectiveEndTime());
                manager.setEndAlarm(task.getId(), checkResult.getEffectiveEndTime());
                break;
        }
    }
}
