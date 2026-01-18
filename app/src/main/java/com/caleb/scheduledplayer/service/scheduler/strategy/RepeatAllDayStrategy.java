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

import java.util.Calendar;

/**
 * 重复全天播放任务调度策略
 * 例如：每周一到五全天播放
 */
public class RepeatAllDayStrategy extends BaseScheduleStrategy {

    @Override
    public TaskType getSupportedType() {
        return TaskType.REPEAT_ALL_DAY;
    }

    @Override
    public ScheduleResult schedule(TaskEntity task, TaskScheduleManager manager) {
        TimeCheckResult checkResult = TaskTimeCalculator.shouldBeActiveNow(task);

        if (checkResult.isActive()) {
            long endTime = checkResult.getEffectiveEndTime(); // 午夜检查时间
            
            // 检查任务是否已经在播放，避免重复启动导致从头播放
            if (AudioPlaybackService.isTaskCurrentlyPlaying(task.getId())) {
                logSchedule(task, "All-day repeat task already playing, skipping start, just updating end alarm");
                manager.setEndAlarm(task.getId(), endTime);
                return ScheduleResult.immediate(endTime);
            }
            
            // 今天需要全天播放
            logSchedule(task, "All-day repeat task should be active today, starting");
            
            long now = System.currentTimeMillis();
            
            startPlaybackAndUpdateState(task, manager, now, endTime);
            
            // 设置午夜检查闹钟
            manager.setEndAlarm(task.getId(), endTime);
            
            return ScheduleResult.immediate(endTime);
        }

        // 今天不执行，调度下一个有效日
        return doScheduleNextExecution(task, manager);
    }

    /**
     * 调度下一次执行
     */
    private ScheduleResult doScheduleNextExecution(TaskEntity task, TaskScheduleManager manager) {
        // 找到下一个重复日
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, 1); // 从明天开始
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 5);
        cal.set(Calendar.MILLISECOND, 0);

        // 最多查找7天
        for (int i = 0; i < 7; i++) {
            if (TaskTimeCalculator.shouldExecuteOnDay(task.getRepeatDays(), cal)) {
                long startTime = cal.getTimeInMillis();
                
                // 结束时间是执行日当天的午夜（即次日00:00:05）
                // 需要从执行日计算，而不是从当前时间计算
                Calendar endCal = (Calendar) cal.clone();
                endCal.add(Calendar.DAY_OF_YEAR, 1);
                // 时间已经是 00:00:05，不需要再设置
                long endTime = endCal.getTimeInMillis();
                
                // 对于全天任务，开始闹钟在当天开始，结束闹钟在午夜
                logSchedule(task, "Scheduling next all-day execution on " 
                        + new java.util.Date(startTime));
                
                manager.setStartAlarm(task.getId(), startTime);
                manager.updateTaskState(task, TaskExecutionState.SCHEDULED);
                
                return ScheduleResult.scheduled(startTime, endTime);
            }
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }

        logWarning(task, "No valid repeat day found in next 7 days");
        return ScheduleResult.noSchedule("No valid repeat day");
    }

    @Override
    public void handleStart(TaskEntity task, TaskScheduleManager manager) {
        if (!validateTaskForStart(task, manager)) {
            return;
        }

        TimeCheckResult checkResult = TaskTimeCalculator.shouldBeActiveNow(task);

        if (checkResult.isActive()) {
            // 今天应该播放，开始全天播放
            logSchedule(task, "Start alarm: today is repeat day, starting all-day playback");
            
            long now = System.currentTimeMillis();
            long endTime = checkResult.getEffectiveEndTime();
            
            startPlaybackAndUpdateState(task, manager, now, endTime);
            manager.setEndAlarm(task.getId(), endTime);
        } else {
            // 今天不是重复日（可能是午夜检查）
            logSchedule(task, "Start alarm: today is not repeat day, stopping if playing");
            
            // 停止播放
            manager.stopPlayback(task);
            manager.updateTaskState(task, TaskExecutionState.IDLE);
            
            // 调度下一个有效日
            doScheduleNextExecution(task, manager);
        }
    }

    @Override
    public void handleStop(TaskEntity task, TaskScheduleManager manager) {
        // 午夜检查闹钟触发（此时已经是新的一天，如00:00:05）
        logSchedule(task, "Midnight check for all-day repeat task");

        // 注意：午夜检查时间是 00:00:05，此时已经进入新的一天
        // 所以应该检查"今天"（新的一天）是否在重复日中
        Calendar today = Calendar.getInstance();
        
        boolean todayValid = TaskTimeCalculator.shouldExecuteOnDay(task.getRepeatDays(), today);
        
        if (todayValid) {
            // 今天（新的一天）也要播放，继续
            logSchedule(task, "Today (new day) is also repeat day, continuing playback");
            
            // 更新结束时间为今天午夜（即明天00:00:05）
            // 使用专门的方法只更新结束时间，避免覆盖用户可能修改的其他字段
            long newEndTime = TaskTimeCalculator.getNextMidnightCheckTime();
            manager.updateExecutionEndTime(task, newEndTime);
            manager.setEndAlarm(task.getId(), newEndTime);
        } else {
            // 今天不播放，停止
            logSchedule(task, "Today is not repeat day, stopping playback");
            stopPlaybackOnly(task, manager);
            
            // 调度下一次（状态会在这里更新为 IDLE -> SCHEDULED）
            manager.updateTaskState(task, TaskExecutionState.IDLE);
            doScheduleNextExecution(task, manager);
        }
    }

    @Override
    protected void handleRebootInActiveRange(TaskEntity task, TaskScheduleManager manager,
            TimeCheckResult checkResult) {
        // 这个方法只有在状态不是 EXECUTING/PAUSED 时才会被调用
        // 检查任务是否已经在播放（即使状态不对）
        if (AudioPlaybackService.isTaskCurrentlyPlaying(task.getId())) {
            logSchedule(task, "All-day task already playing, skipping start");
            manager.setEndAlarm(task.getId(), checkResult.getEffectiveEndTime());
            return;
        }
        
        logSchedule(task, "Starting all-day playback after reboot");
        // 开始新的执行
        startPlaybackAndUpdateState(task, manager,
                System.currentTimeMillis(), checkResult.getEffectiveEndTime());
        
        manager.setEndAlarm(task.getId(), checkResult.getEffectiveEndTime());
    }
}
