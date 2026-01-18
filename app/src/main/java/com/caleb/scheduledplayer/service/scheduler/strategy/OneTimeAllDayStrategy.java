package com.caleb.scheduledplayer.service.scheduler.strategy;

import com.caleb.scheduledplayer.data.entity.TaskEntity;
import com.caleb.scheduledplayer.service.player.AudioPlaybackService;
import com.caleb.scheduledplayer.service.scheduler.BaseScheduleStrategy;
import com.caleb.scheduledplayer.service.scheduler.ScheduleResult;
import com.caleb.scheduledplayer.service.scheduler.TaskExecutionState;
import com.caleb.scheduledplayer.service.scheduler.TaskScheduleManager;
import com.caleb.scheduledplayer.service.scheduler.TaskTimeCalculator;
import com.caleb.scheduledplayer.service.scheduler.TaskType;
import com.caleb.scheduledplayer.service.scheduler.TimeCheckResult;

/**
 * 一次性全天播放任务调度策略
 * 例如：今天全天播放一次
 */
public class OneTimeAllDayStrategy extends BaseScheduleStrategy {

    @Override
    public TaskType getSupportedType() {
        return TaskType.ONE_TIME_ALL_DAY;
    }

    @Override
    public ScheduleResult schedule(TaskEntity task, TaskScheduleManager manager) {
        TimeCheckResult checkResult = TaskTimeCalculator.shouldBeActiveNow(task);

        if (checkResult.isActive()) {
            long endTime = checkResult.getEffectiveEndTime(); // 午夜时间
            
            // 检查任务是否已经在播放，避免重复启动导致从头播放
            if (AudioPlaybackService.isTaskCurrentlyPlaying(task.getId())) {
                logSchedule(task, "All-day task already playing, skipping start, just updating end alarm");
                manager.setEndAlarm(task.getId(), endTime);
                return ScheduleResult.immediate(endTime);
            }
            
            // 今天需要执行，立即开始全天播放
            logSchedule(task, "All-day task should be active today, starting immediately");
            
            long now = System.currentTimeMillis();
            
            startPlaybackAndUpdateState(task, manager, now, endTime);
            
            // 设置午夜检查闹钟
            manager.setEndAlarm(task.getId(), endTime);
            
            return ScheduleResult.immediate(endTime);
        }

        // 今天不执行（一次性全天任务如果今天不执行，可能已过期）
        switch (checkResult.getReason()) {
            case ALL_DAY_NOT_TODAY:
                // 一次性任务今天不执行，可能是之前创建但未启用
                // 或者已经过了今天
                logSchedule(task, "One-time all-day task not for today, disabling");
                manager.disableTask(task);
                return ScheduleResult.noSchedule("Not for today");

            case TASK_COMPLETED:
            case TASK_DISABLED:
                logSchedule(task, "Task already completed or disabled");
                return ScheduleResult.noSchedule(checkResult.getReason().getDescription());

            default:
                logSchedule(task, "Not scheduling, reason: " + checkResult.getReason());
                return ScheduleResult.noSchedule(checkResult.getReason().getDescription());
        }
    }

    @Override
    public void handleStart(TaskEntity task, TaskScheduleManager manager) {
        // 全天播放任务通常不会触发开始闹钟（除非是午夜检查）
        // 午夜检查时，如果是一次性任务，应该停止而不是开始
        
        if (!validateTaskForStart(task, manager)) {
            return;
        }

        TimeCheckResult checkResult = TaskTimeCalculator.shouldBeActiveNow(task);
        
        if (checkResult.isActive()) {
            // 今天仍然应该播放（不太可能，因为一次性任务午夜后应该停止）
            logSchedule(task, "Midnight check: still active, continuing playback");
        } else {
            // 午夜过后，停止并禁用
            logSchedule(task, "Midnight check: one-time all-day task completed, stopping");
            stopPlaybackAndUpdateState(task, manager);
            disableOneTimeTask(task, manager);
        }
    }

    @Override
    public void handleStop(TaskEntity task, TaskScheduleManager manager) {
        logSchedule(task, "Stop alarm triggered for all-day task");
        
        stopPlaybackAndUpdateState(task, manager);
        
        // 一次性任务完成后禁用
        disableOneTimeTask(task, manager);
    }

    @Override
    protected void handleRebootInActiveRange(TaskEntity task, TaskScheduleManager manager,
            TimeCheckResult checkResult) {
        // 检查任务是否已经在播放
        if (AudioPlaybackService.isTaskCurrentlyPlaying(task.getId())) {
            logSchedule(task, "All-day task already playing, skipping start");
            manager.setEndAlarm(task.getId(), checkResult.getEffectiveEndTime());
            return;
        }
        
        TaskExecutionState currentState = task.getExecutionStateEnum();
        
        switch (currentState) {
            case EXECUTING:
            case PAUSED:
            case SCHEDULED:
            case IDLE:
                // 今天应该全天播放，恢复
                logSchedule(task, "Resuming all-day playback after reboot");
                startPlaybackAndUpdateState(task, manager,
                        System.currentTimeMillis(), checkResult.getEffectiveEndTime());
                manager.setEndAlarm(task.getId(), checkResult.getEffectiveEndTime());
                break;

            case COMPLETED:
            case DISABLED:
                // 已完成或已禁用，不恢复
                logSchedule(task, "Already completed or disabled, not resuming");
                break;
        }
    }
}
