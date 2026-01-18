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
 * 一次性跨天任务调度策略
 * 例如：今晚 22:00 到明天 02:00 播放一次
 */
public class OneTimeCrossDayStrategy extends BaseScheduleStrategy {

    @Override
    public TaskType getSupportedType() {
        return TaskType.ONE_TIME_CROSS_DAY;
    }

    @Override
    public ScheduleResult schedule(TaskEntity task, TaskScheduleManager manager) {
        TimeCheckResult checkResult = TaskTimeCalculator.shouldBeActiveNow(task);

        if (checkResult.isActive()) {
            long endTime = checkResult.getEffectiveEndTime();
            
            // 检查任务是否已经在播放，避免重复启动导致从头播放
            if (AudioPlaybackService.isTaskCurrentlyPlaying(task.getId())) {
                logSchedule(task, "Cross-day task already playing, skipping start, just updating end alarm");
                manager.setEndAlarm(task.getId(), endTime);
                return ScheduleResult.immediate(endTime);
            }
            
            // 当前在时间范围内（晚间或凌晨部分）
            logSchedule(task, "Currently in time range (" + checkResult.getReason() 
                    + "), starting immediately");
            
            long now = System.currentTimeMillis();
            
            startPlaybackAndUpdateState(task, manager, now, endTime);
            manager.setEndAlarm(task.getId(), endTime);
            
            return ScheduleResult.immediate(endTime);
        }

        // 不在时间范围内
        switch (checkResult.getReason()) {
            case ONE_TIME_MORNING_NO_STATE:
                // 凌晨部分但无执行状态，可能任务昨晚未执行或已完成
                // 设置今天的结束闹钟（如果任务正在播放会正确结束）
                // 不设置开始闹钟，防止今晚再次执行
                logSchedule(task, "In morning part but no execution state, setting end alarm only");
                long todayEndTime = TaskTimeCalculator.calculateCurrentEndTime(task);
                manager.setEndAlarm(task.getId(), todayEndTime);
                manager.cancelStartAlarm(task.getId());
                return ScheduleResult.endOnly(todayEndTime);

            case NOT_IN_RANGE:
                // 白天部分，计算今晚的开始时间
                long startTime = TaskTimeCalculator.calculateNextStartTime(task);
                if (startTime < 0) {
                    // 没有有效的开始时间
                    logSchedule(task, "No valid start time, disabling");
                    manager.disableTask(task);
                    return ScheduleResult.noSchedule("No valid start time");
                }
                
                long endTime = TaskTimeCalculator.calculateEndTimeForStart(task, startTime);
                
                logSchedule(task, "Scheduling start at " + new java.util.Date(startTime) 
                        + ", end at " + new java.util.Date(endTime));
                manager.setStartAlarm(task.getId(), startTime);
                manager.setEndAlarm(task.getId(), endTime);
                
                return ScheduleResult.scheduled(startTime, endTime);

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
            return;
        }

        long now = System.currentTimeMillis();
        long endTime = checkResult.getEffectiveEndTime();

        logSchedule(task, "Start alarm triggered (cross-day), starting playback until " 
                + new java.util.Date(endTime));
        startPlaybackAndUpdateState(task, manager, now, endTime);
        
        // 设置结束闹钟
        manager.setEndAlarm(task.getId(), endTime);
    }

    @Override
    public void handleStop(TaskEntity task, TaskScheduleManager manager) {
        logSchedule(task, "Stop alarm triggered (cross-day)");
        
        stopPlaybackAndUpdateState(task, manager);
        
        // 一次性任务完成后禁用
        disableOneTimeTask(task, manager);
    }

    @Override
    protected void handleRebootInActiveRange(TaskEntity task, TaskScheduleManager manager,
            TimeCheckResult checkResult) {
        // 检查任务是否已经在播放
        if (AudioPlaybackService.isTaskCurrentlyPlaying(task.getId())) {
            logSchedule(task, "Cross-day task already playing, skipping start");
            manager.setEndAlarm(task.getId(), checkResult.getEffectiveEndTime());
            return;
        }
        
        // 一次性跨天任务重启后在时间范围内
        TaskExecutionState currentState = task.getExecutionStateEnum();
        
        switch (currentState) {
            case EXECUTING:
            case PAUSED:
            case SCHEDULED:
                // 之前已调度或在执行
                if (checkResult.getReason() == TimeCheckResult.ActiveReason.IN_CROSS_DAY_EVENING) {
                    // 晚间部分，开始执行
                    logSchedule(task, "Starting cross-day playback after reboot (evening part)");
                    startPlaybackAndUpdateState(task, manager,
                            System.currentTimeMillis(), checkResult.getEffectiveEndTime());
                    manager.setEndAlarm(task.getId(), checkResult.getEffectiveEndTime());
                } else if (currentState == TaskExecutionState.EXECUTING || currentState == TaskExecutionState.PAUSED) {
                    // 凌晨部分，之前在执行中，恢复播放
                    logSchedule(task, "Resuming cross-day playback after reboot (morning part)");
                    startPlaybackAndUpdateState(task, manager,
                            System.currentTimeMillis(), checkResult.getEffectiveEndTime());
                    manager.setEndAlarm(task.getId(), checkResult.getEffectiveEndTime());
                } else {
                    // 凌晨部分但之前只是调度状态，说明昨晚未执行
                    logSchedule(task, "Was scheduled but in morning part, not starting");
                }
                break;

            case COMPLETED:
            case DISABLED:
                // 已完成或已禁用，不恢复
                logSchedule(task, "Already completed or disabled, not resuming");
                break;

            default:
                // IDLE 状态
                if (checkResult.getReason() == TimeCheckResult.ActiveReason.IN_CROSS_DAY_EVENING) {
                    // 晚间部分，可以开始
                    logSchedule(task, "IDLE state in evening part, starting playback");
                    startPlaybackAndUpdateState(task, manager,
                            System.currentTimeMillis(), checkResult.getEffectiveEndTime());
                    manager.setEndAlarm(task.getId(), checkResult.getEffectiveEndTime());
                } else {
                    // 凌晨部分，IDLE 状态不启动
                    logSchedule(task, "IDLE state in morning part, not starting");
                }
                break;
        }
    }

    @Override
    public void handleReboot(TaskEntity task, TaskScheduleManager manager) {
        if (!validateTaskForStart(task, manager)) {
            return;
        }

        TimeCheckResult checkResult = TaskTimeCalculator.shouldBeActiveNow(task);

        if (checkResult.isActive()) {
            handleRebootInActiveRange(task, manager, checkResult);
        } else if (checkResult.getReason() == TimeCheckResult.ActiveReason.ONE_TIME_MORNING_NO_STATE) {
            // 凌晨部分但无执行状态
            // 设置结束闹钟，不设置开始闹钟
            logSchedule(task, "Reboot in morning part without execution state, setting end alarm only");
            long todayEndTime = TaskTimeCalculator.calculateCurrentEndTime(task);
            manager.setEndAlarm(task.getId(), todayEndTime);
            manager.cancelStartAlarm(task.getId());
        } else {
            // 不在时间范围内，重新调度
            logSchedule(task, "Not active after reboot, reason=" + checkResult.getReason() 
                    + ", rescheduling");
            schedule(task, manager);
        }
    }
}
