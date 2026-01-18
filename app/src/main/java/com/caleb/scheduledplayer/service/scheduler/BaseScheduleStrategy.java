package com.caleb.scheduledplayer.service.scheduler;

import com.caleb.scheduledplayer.util.AppLogger;

import com.caleb.scheduledplayer.data.entity.TaskEntity;
import com.caleb.scheduledplayer.service.player.AudioPlaybackService;

/**
 * 调度策略基类
 * 提供通用的日志记录、状态更新等功能
 */
public abstract class BaseScheduleStrategy implements ScheduleStrategy {

    protected final String TAG;

    protected BaseScheduleStrategy() {
        this.TAG = getClass().getSimpleName();
    }

    /**
     * 记录调度日志
     */
    protected void logSchedule(TaskEntity task, String message) {
        AppLogger.d(TAG, "Task " + task.getId() + " [" + task.getName() + "]: " + message);
    }

    /**
     * 记录警告日志
     */
    protected void logWarning(TaskEntity task, String message) {
        AppLogger.w(TAG, "Task " + task.getId() + " [" + task.getName() + "]: " + message);
    }

    /**
     * 记录错误日志
     */
    protected void logError(TaskEntity task, String message, Throwable e) {
        AppLogger.e(TAG, "Task " + task.getId() + " [" + task.getName() + "]: " + message, e);
    }

    /**
     * 启动播放并更新状态为执行中（带并发检查）
     * 如果并发达上限，会进入等待状态并设置重试闹钟
     * 
     * @return true 如果成功启动播放，false 如果进入等待状态
     */
    protected boolean startPlaybackAndUpdateState(TaskEntity task, TaskScheduleManager manager, 
            long executionStart, long executionEnd) {
        logSchedule(task, "Attempting to start playback with concurrency check");
        
        // 使用带并发检查的启动方法
        boolean started = manager.tryStartPlaybackWithConcurrencyCheck(task, executionStart, executionEnd);
        
        if (!started) {
            logSchedule(task, "Playback deferred due to concurrency limit, waiting for slot");
        }
        
        return started;
    }

    /**
     * 停止播放并更新状态为已完成
     * 用于一次性任务结束时
     */
    protected void stopPlaybackAndUpdateState(TaskEntity task, TaskScheduleManager manager) {
        logSchedule(task, "Stopping playback");
        
        // 停止播放
        manager.stopPlayback(task);
        
        // 更新状态为已完成
        manager.updateTaskState(task, TaskExecutionState.COMPLETED);
    }

    /**
     * 停止播放但不更新状态
     * 用于重复任务结束时，调用方会自行管理状态
     */
    protected void stopPlaybackOnly(TaskEntity task, TaskScheduleManager manager) {
        logSchedule(task, "Stopping playback (state managed by caller)");
        manager.stopPlayback(task);
    }

    /**
     * 禁用一次性任务
     */
    protected void disableOneTimeTask(TaskEntity task, TaskScheduleManager manager) {
        if (task.isOneTime()) {
            logSchedule(task, "One-time task completed, disabling");
            manager.disableTask(task);
        }
    }

    /**
     * 调度下一次执行（重复任务）
     */
    protected ScheduleResult scheduleNextExecution(TaskEntity task, TaskScheduleManager manager) {
        if (task.isOneTime()) {
            // 一次性任务没有下次执行
            return ScheduleResult.noSchedule("One-time task, no next execution");
        }

        // 重置状态为空闲
        manager.updateTaskState(task, TaskExecutionState.IDLE);
        
        // 重新调度
        return schedule(task, manager);
    }

    /**
     * 验证任务是否可以开始执行
     */
    protected boolean validateTaskForStart(TaskEntity task, TaskScheduleManager manager) {
        if (task == null) {
            AppLogger.w(TAG, "Task is null, cannot start");
            return false;
        }

        if (!task.isEnabled()) {
            logWarning(task, "Task is disabled, cannot start");
            return false;
        }

        return true;
    }

    /**
     * 通用的重启恢复逻辑
     */
    @Override
    public void handleReboot(TaskEntity task, TaskScheduleManager manager) {
        if (!validateTaskForStart(task, manager)) {
            return;
        }

        TaskExecutionState currentState = task.getExecutionStateEnum();
        
        // 如果任务处于等待空位状态，重新设置重试闹钟
        if (currentState == TaskExecutionState.WAITING_SLOT) {
            logSchedule(task, "Task was waiting for slot before reboot, retrying now");
            handleRetryStart(task, manager);
            return;
        }
        
        // 如果任务处于 SKIPPED 状态，检查是否有下一次执行需要调度
        if (currentState == TaskExecutionState.SKIPPED) {
            logSchedule(task, "Task was skipped before reboot, rescheduling");
            // 保持 SKIPPED 状态，只重新调度闹钟
            schedule(task, manager);
            return;
        }

        TimeCheckResult checkResult = TaskTimeCalculator.shouldBeActiveNow(task);

        if (checkResult.isActive()) {
            // 应该活跃
            if (currentState == TaskExecutionState.EXECUTING 
                    || currentState == TaskExecutionState.PAUSED) {
                // 之前在执行中，检查是否需要恢复播放
                // 使用 isTaskCurrentlyPlaying 检查**特定任务**是否正在播放
                if (AudioPlaybackService.isTaskCurrentlyPlaying(task.getId())) {
                    logSchedule(task, "Task is currently playing, skipping startPlayback for state=" + currentState 
                            + " - playback should continue normally");
                    // 只确保结束闹钟正确设置
                    manager.setEndAlarm(task.getId(), checkResult.getEffectiveEndTime());
                } else {
                    // 任务没有在播放（可能是设备重启或进程被杀死），需要恢复播放
                    logSchedule(task, "Task not playing, resuming playback after reboot, state=" + currentState);
                    manager.startPlayback(task);
                    manager.setEndAlarm(task.getId(), checkResult.getEffectiveEndTime());
                }
            } else {
                // 状态不是执行中，但时间范围内，根据具体策略处理
                handleRebootInActiveRange(task, manager, checkResult);
            }
        } else {
            // 不应该活跃，重新调度
            logSchedule(task, "Not active after reboot, reason=" + checkResult.getReason() 
                    + ", rescheduling");
            schedule(task, manager);
        }
    }

    /**
     * 处理重启后在活跃时间范围内但状态不是执行中的情况
     * 子类可以覆写此方法实现特殊逻辑
     */
    protected void handleRebootInActiveRange(TaskEntity task, TaskScheduleManager manager,
            TimeCheckResult checkResult) {
        // 检查任务是否已经在播放
        if (AudioPlaybackService.isTaskCurrentlyPlaying(task.getId())) {
            logSchedule(task, "Task already playing, skipping start");
            manager.setEndAlarm(task.getId(), checkResult.getEffectiveEndTime());
            return;
        }
        
        // 默认行为：启动播放
        logSchedule(task, "In active range after reboot, starting playback");
        startPlaybackAndUpdateState(task, manager, 
                System.currentTimeMillis(), checkResult.getEffectiveEndTime());
        manager.setEndAlarm(task.getId(), checkResult.getEffectiveEndTime());
    }

    /**
     * 处理重试启动（因并发限制等待后的重试）
     * 默认实现：检查任务是否仍应活跃，如果是则尝试启动
     */
    @Override
    public void handleRetryStart(TaskEntity task, TaskScheduleManager manager) {
        logSchedule(task, "Retry start triggered");
        
        // 检查任务是否仍应活跃
        TimeCheckResult checkResult = TaskTimeCalculator.shouldBeActiveNow(task);
        if (!checkResult.isActive()) {
            logSchedule(task, "Task should not be active now, skipping retry. Reason: " + checkResult.getReason());
            manager.handleSkipDueToConcurrency(task);
            return;
        }
        
        // 重新尝试启动
        long now = System.currentTimeMillis();
        long endTime = task.getCurrentExecutionEnd();
        if (endTime <= 0) {
            endTime = checkResult.getEffectiveEndTime();
        }
        
        boolean started = manager.tryStartPlaybackWithConcurrencyCheck(task, now, endTime);
        
        if (started) {
            logSchedule(task, "Retry start succeeded, setting end alarm");
            manager.setEndAlarm(task.getId(), endTime);
        } else {
            logSchedule(task, "Retry start still blocked by concurrency limit, will retry again");
            // tryStartPlaybackWithConcurrencyCheck 已经设置了下一次重试闹钟
        }
    }
}
