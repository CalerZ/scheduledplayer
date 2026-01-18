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
 * 重复跨天任务调度策略
 * 例如：每周五六 22:00-02:00
 * 也用于每天跨天任务（EVERYDAY_CROSS_DAY）
 */
public class RepeatCrossDayStrategy extends BaseScheduleStrategy {

    @Override
    public TaskType getSupportedType() {
        return TaskType.REPEAT_CROSS_DAY;
    }

    @Override
    public ScheduleResult schedule(TaskEntity task, TaskScheduleManager manager) {
        TimeCheckResult checkResult = TaskTimeCalculator.shouldBeActiveNow(task);

        if (checkResult.isActive()) {
            long endTime = checkResult.getEffectiveEndTime();
            
            // 检查任务是否已经在播放，避免重复启动导致从头播放
            if (AudioPlaybackService.isTaskCurrentlyPlaying(task.getId())) {
                logSchedule(task, "Task already playing, skipping start, just updating alarms");
                manager.setEndAlarm(task.getId(), endTime);
                scheduleNextStartAlarm(task, manager);
                return ScheduleResult.immediate(endTime);
            }
            
            // 当前在时间范围内（晚间或凌晨部分）
            logSchedule(task, "Currently in time range (" + checkResult.getReason() 
                    + "), starting immediately");
            
            long now = System.currentTimeMillis();
            
            startPlaybackAndUpdateState(task, manager, now, endTime);
            manager.setEndAlarm(task.getId(), endTime);
            
            // 重复任务：调度下一次开始
            scheduleNextStartAlarm(task, manager);
            
            return ScheduleResult.immediate(endTime);
        }

        // 不在时间范围内，调度下一次
        return doScheduleNextExecution(task, manager);
    }

    /**
     * 调度下一次开始闹钟
     */
    private void scheduleNextStartAlarm(TaskEntity task, TaskScheduleManager manager) {
        long nextStartTime = TaskTimeCalculator.calculateNextStartTime(task);
        if (nextStartTime > 0) {
            logSchedule(task, "Scheduling next start at " + new java.util.Date(nextStartTime));
            manager.setStartAlarm(task.getId(), nextStartTime);
        }
    }

    /**
     * 调度下一次执行
     */
    private ScheduleResult doScheduleNextExecution(TaskEntity task, TaskScheduleManager manager) {
        long startTime = TaskTimeCalculator.calculateNextStartTime(task);
        if (startTime < 0) {
            logWarning(task, "No valid next start time found");
            return ScheduleResult.noSchedule("No valid start time");
        }

        long endTime = TaskTimeCalculator.calculateEndTimeForStart(task, startTime);

        logSchedule(task, "Scheduling next execution (cross-day): start=" 
                + new java.util.Date(startTime) + ", end=" + new java.util.Date(endTime));
        
        manager.setStartAlarm(task.getId(), startTime);
        manager.setEndAlarm(task.getId(), endTime);
        manager.updateTaskState(task, TaskExecutionState.SCHEDULED);

        return ScheduleResult.scheduled(startTime, endTime);
    }

    @Override
    public void handleStart(TaskEntity task, TaskScheduleManager manager) {
        if (!validateTaskForStart(task, manager)) {
            return;
        }

        // 二次验证
        TimeCheckResult checkResult = TaskTimeCalculator.shouldBeActiveNow(task);
        if (!checkResult.isActive()) {
            logWarning(task, "Start alarm triggered but not valid: " + checkResult.getReason());
            doScheduleNextExecution(task, manager);
            return;
        }

        long now = System.currentTimeMillis();
        long endTime = checkResult.getEffectiveEndTime();

        logSchedule(task, "Start alarm triggered (cross-day), starting playback until " 
                + new java.util.Date(endTime));
        startPlaybackAndUpdateState(task, manager, now, endTime);
        
        manager.setEndAlarm(task.getId(), endTime);
        
        // 调度下一次开始
        scheduleNextStartAlarm(task, manager);
    }

    @Override
    public void handleStop(TaskEntity task, TaskScheduleManager manager) {
        logSchedule(task, "Stop alarm triggered (cross-day)");
        
        // 重复任务：停止播放，直接设置为 IDLE 并调度下一次
        stopPlaybackOnly(task, manager);
        manager.updateTaskState(task, TaskExecutionState.IDLE);
        doScheduleNextExecution(task, manager);
    }

    @Override
    protected void handleRebootInActiveRange(TaskEntity task, TaskScheduleManager manager,
            TimeCheckResult checkResult) {
        // 检查任务是否已经在播放
        if (AudioPlaybackService.isTaskCurrentlyPlaying(task.getId())) {
            logSchedule(task, "Task already playing after reboot, skipping start");
            manager.setEndAlarm(task.getId(), checkResult.getEffectiveEndTime());
            scheduleNextStartAlarm(task, manager);
            return;
        }
        
        logSchedule(task, "In active range after reboot (" + checkResult.getReason() 
                + "), starting playback");
        
        // 开始新的执行
        startPlaybackAndUpdateState(task, manager,
                System.currentTimeMillis(), checkResult.getEffectiveEndTime());
        
        manager.setEndAlarm(task.getId(), checkResult.getEffectiveEndTime());
        
        // 调度下一次开始
        scheduleNextStartAlarm(task, manager);
    }

    @Override
    public void handleReboot(TaskEntity task, TaskScheduleManager manager) {
        if (!validateTaskForStart(task, manager)) {
            return;
        }

        TimeCheckResult checkResult = TaskTimeCalculator.shouldBeActiveNow(task);

        if (checkResult.isActive()) {
            handleRebootInActiveRange(task, manager, checkResult);
        } else {
            // 不在时间范围内，重新调度
            logSchedule(task, "Not active after reboot, reason=" + checkResult.getReason() 
                    + ", rescheduling");
            schedule(task, manager);
        }
    }
}
