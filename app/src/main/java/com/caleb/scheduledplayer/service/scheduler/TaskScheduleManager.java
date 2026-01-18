package com.caleb.scheduledplayer.service.scheduler;

import android.content.Context;

import com.caleb.scheduledplayer.util.AppLogger;

import com.caleb.scheduledplayer.data.dao.TaskDao;
import com.caleb.scheduledplayer.data.database.AppDatabase;
import com.caleb.scheduledplayer.data.entity.TaskEntity;
import com.caleb.scheduledplayer.service.player.AudioPlaybackService;
import com.caleb.scheduledplayer.service.scheduler.strategy.OneTimeAllDayStrategy;
import com.caleb.scheduledplayer.service.scheduler.strategy.OneTimeCrossDayStrategy;
import com.caleb.scheduledplayer.service.scheduler.strategy.OneTimeNormalStrategy;
import com.caleb.scheduledplayer.service.scheduler.strategy.RepeatAllDayStrategy;
import com.caleb.scheduledplayer.service.scheduler.strategy.RepeatCrossDayStrategy;
import com.caleb.scheduledplayer.service.scheduler.strategy.RepeatNormalStrategy;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务调度管理器
 * 统一管理任务的调度、执行和状态转换
 */
public class TaskScheduleManager {

    private static final String TAG = "TaskScheduleManager";

    private static volatile TaskScheduleManager instance;

    private final Context context;
    private final TaskDao taskDao;
    private final AlarmScheduler alarmScheduler;
    private final ConcurrencyManager concurrencyManager;
    private final Map<TaskType, ScheduleStrategy> strategies;
    
    // 任务锁映射，确保对同一任务的操作是线程安全的
    private final ConcurrentHashMap<Long, Object> taskLocks = new ConcurrentHashMap<>();

    private TaskScheduleManager(Context context) {
        this.context = context.getApplicationContext();
        this.taskDao = AppDatabase.getInstance(context).taskDao();
        this.alarmScheduler = new AlarmScheduler(context);
        this.concurrencyManager = new ConcurrencyManager(taskDao);
        this.strategies = initStrategies();
    }

    /**
     * 获取单例实例
     */
    public static TaskScheduleManager getInstance(Context context) {
        if (instance == null) {
            synchronized (TaskScheduleManager.class) {
                if (instance == null) {
                    instance = new TaskScheduleManager(context);
                }
            }
        }
        return instance;
    }
    
    /**
     * 获取任务锁
     * 确保对同一任务的并发操作是线程安全的
     */
    private Object getTaskLock(long taskId) {
        return taskLocks.computeIfAbsent(taskId, k -> new Object());
    }
    
    /**
     * 清理任务锁（任务删除时调用）
     */
    public void removeTaskLock(long taskId) {
        taskLocks.remove(taskId);
    }

    /**
     * 初始化所有策略
     */
    private Map<TaskType, ScheduleStrategy> initStrategies() {
        Map<TaskType, ScheduleStrategy> map = new EnumMap<>(TaskType.class);

        // 一次性任务策略
        map.put(TaskType.ONE_TIME_NORMAL, new OneTimeNormalStrategy());
        map.put(TaskType.ONE_TIME_CROSS_DAY, new OneTimeCrossDayStrategy());
        map.put(TaskType.ONE_TIME_ALL_DAY, new OneTimeAllDayStrategy());

        // 重复任务策略
        map.put(TaskType.REPEAT_NORMAL, new RepeatNormalStrategy());
        map.put(TaskType.REPEAT_CROSS_DAY, new RepeatCrossDayStrategy());
        map.put(TaskType.REPEAT_ALL_DAY, new RepeatAllDayStrategy());

        // 每天任务（复用重复任务策略，行为相同）
        map.put(TaskType.EVERYDAY_NORMAL, new RepeatNormalStrategy());
        map.put(TaskType.EVERYDAY_CROSS_DAY, new RepeatCrossDayStrategy());

        return map;
    }

    // ==================== 核心调度方法 ====================

    /**
     * 调度单个任务
     * 
     * @param task 任务实体
     * @return ScheduleResult 调度结果
     */
    public ScheduleResult scheduleTask(TaskEntity task) {
        if (task == null) {
            AppLogger.getInstance().w(TAG, "Cannot schedule null task");
            return ScheduleResult.noSchedule("Task is null");
        }

        synchronized (getTaskLock(task.getId())) {
            if (!task.isEnabled()) {
                AppLogger.getInstance().d(TAG, "Task " + task.getId() + " is disabled, cancelling alarms");
                cancelTask(task);
                return ScheduleResult.noSchedule("Task is disabled");
            }

            TaskType type = TaskClassifier.classify(task);
            ScheduleStrategy strategy = strategies.get(type);

            if (strategy == null) {
                AppLogger.getInstance().e(TAG, "No strategy found for task type: " + type);
                return ScheduleResult.noSchedule("No strategy for type " + type);
            }

            AppLogger.getInstance().d(TAG, "Scheduling task " + task.getId() + " [" + task.getName() 
                    + "] with type " + type);
            
            return strategy.schedule(task, this);
        }
    }

    /**
     * 处理开始闹钟触发
     * 
     * @param taskId 任务ID
     */
    public void handleStartAlarm(long taskId) {
        AppLogger.getInstance().d(TAG, "Handling start alarm for task " + taskId);
        
        synchronized (getTaskLock(taskId)) {
            TaskEntity task = taskDao.getTaskByIdSync(taskId);
            if (task == null) {
                AppLogger.getInstance().w(TAG, "Task " + taskId + " not found");
                return;
            }

            if (!task.isEnabled()) {
                AppLogger.getInstance().d(TAG, "Task " + taskId + " is disabled, ignoring start alarm");
                return;
            }

            TaskType type = TaskClassifier.classify(task);
            ScheduleStrategy strategy = strategies.get(type);

            if (strategy != null) {
                strategy.handleStart(task, this);
            }
        }
    }

    /**
     * 处理结束闹钟触发
     * 
     * @param taskId 任务ID
     */
    public void handleStopAlarm(long taskId) {
        AppLogger.getInstance().d(TAG, "Handling stop alarm for task " + taskId);
        
        synchronized (getTaskLock(taskId)) {
            TaskEntity task = taskDao.getTaskByIdSync(taskId);
            if (task == null) {
                AppLogger.getInstance().w(TAG, "Task " + taskId + " not found");
                return;
            }

            TaskExecutionState currentState = task.getExecutionStateEnum();
            
            // 如果任务还在等待空位，说明从未成功启动播放
            // 应该标记为 SKIPPED 而不是正常结束
            if (currentState == TaskExecutionState.WAITING_SLOT) {
                AppLogger.getInstance().d(TAG, "Task " + taskId + " was waiting for slot when end time reached, marking as SKIPPED");
                handleSkipDueToConcurrency(task);
                return;
            }

            TaskType type = TaskClassifier.classify(task);
            ScheduleStrategy strategy = strategies.get(type);

            if (strategy != null) {
                strategy.handleStop(task, this);
            }
        }
    }

    /**
     * 重新调度所有启用的任务
     * 设备重启后或定期检查时调用
     */
    public void rescheduleAllTasks() {
        AppLogger.getInstance().d(TAG, "Rescheduling all tasks");
        
        List<TaskEntity> enabledTasks = taskDao.getEnabledTasksSync();
        AppLogger.getInstance().d(TAG, "Found " + enabledTasks.size() + " enabled tasks");

        for (TaskEntity task : enabledTasks) {
            // 为每个任务加锁，避免与其他操作冲突
            synchronized (getTaskLock(task.getId())) {
                try {
                    TaskType type = TaskClassifier.classify(task);
                    ScheduleStrategy strategy = strategies.get(type);

                    if (strategy != null) {
                        strategy.handleReboot(task, this);
                    }
                } catch (Exception e) {
                    AppLogger.getInstance().e(TAG, "Error rescheduling task " + task.getId(), e);
                }
            }
        }
    }

    /**
     * 取消任务的所有调度
     * 包括开始闹钟、结束闹钟、重试闹钟
     * 
     * @param task 任务实体
     */
    public void cancelTask(TaskEntity task) {
        if (task == null) return;
        
        AppLogger.getInstance().d(TAG, "Cancelling task " + task.getId());
        // cancelAlarms 已经包含了取消重试闹钟
        alarmScheduler.cancelAlarms(task.getId());
        stopPlayback(task);
    }

    /**
     * 取消任务的所有调度（通过任务ID）
     * 包括开始闹钟、结束闹钟、重试闹钟
     * 
     * @param taskId 任务ID
     */
    public void cancelTask(long taskId) {
        AppLogger.getInstance().d(TAG, "Cancelling task " + taskId);
        // cancelAlarms 已经包含了取消重试闹钟
        alarmScheduler.cancelAlarms(taskId);
        AudioPlaybackService.stopTaskPlayback(context, taskId);
    }

    // ==================== 辅助方法（供策略调用） ====================

    /**
     * 启动任务播放
     */
    public void startPlayback(TaskEntity task) {
        AppLogger.getInstance().d(TAG, "Starting playback for task " + task.getId());
        AudioPlaybackService.startTaskPlayback(context, task.getId());
    }

    /**
     * 停止任务播放
     */
    public void stopPlayback(TaskEntity task) {
        AppLogger.getInstance().d(TAG, "Stopping playback for task " + task.getId());
        AudioPlaybackService.stopTaskPlayback(context, task.getId());
    }

    /**
     * 更新任务执行状态
     */
    public void updateTaskState(TaskEntity task, TaskExecutionState state) {
        AppLogger.getInstance().d(TAG, "Updating task " + task.getId() + " state to " + state);
        task.setExecutionStateEnum(state);
        taskDao.updateExecutionState(task.getId(), state.getValue(), System.currentTimeMillis());
    }

    /**
     * 更新任务执行状态和时间信息
     */
    public void updateTaskExecutionInfo(TaskEntity task, TaskExecutionState state,
            long executionStart, long executionEnd) {
        AppLogger.getInstance().d(TAG, "Updating task " + task.getId() + " execution info: state=" + state 
                + ", start=" + executionStart + ", end=" + executionEnd);
        task.setExecutionStateEnum(state);
        task.setCurrentExecutionStart(executionStart);
        task.setCurrentExecutionEnd(executionEnd);
        taskDao.updateExecutionInfo(task.getId(), state.getValue(), 
                executionStart, executionEnd, System.currentTimeMillis());
    }

    /**
     * 禁用任务
     */
    public void disableTask(TaskEntity task) {
        AppLogger.getInstance().d(TAG, "Disabling task " + task.getId());
        task.setEnabled(false);
        task.setExecutionStateEnum(TaskExecutionState.DISABLED);
        // 使用新接口同时更新 enabled 和 execution_state，保持数据一致性
        taskDao.disableTaskWithState(task.getId(), System.currentTimeMillis());
        alarmScheduler.cancelAlarms(task.getId());
    }

    /**
     * 重置任务执行状态
     */
    public void resetTaskState(TaskEntity task) {
        AppLogger.getInstance().d(TAG, "Resetting task " + task.getId() + " state");
        task.resetExecutionState();
        taskDao.resetExecutionState(task.getId(), System.currentTimeMillis());
    }

    /**
     * 设置开始闹钟
     */
    public void setStartAlarm(long taskId, long triggerTime) {
        alarmScheduler.setStartAlarm(taskId, triggerTime);
    }

    /**
     * 设置结束闹钟
     */
    public void setEndAlarm(long taskId, long triggerTime) {
        alarmScheduler.setEndAlarm(taskId, triggerTime);
    }

    /**
     * 取消开始闹钟
     */
    public void cancelStartAlarm(long taskId) {
        alarmScheduler.cancelStartAlarm(taskId);
    }

    /**
     * 取消结束闹钟
     */
    public void cancelEndAlarm(long taskId) {
        alarmScheduler.cancelEndAlarm(taskId);
    }

    /**
     * 取消所有闹钟
     */
    public void cancelAlarms(long taskId) {
        alarmScheduler.cancelAlarms(taskId);
    }

    /**
     * 保存任务到数据库
     */
    public void saveTask(TaskEntity task) {
        taskDao.update(task);
    }

    /**
     * 只更新执行结束时间（避免覆盖用户可能修改的其他字段）
     */
    public void updateExecutionEndTime(TaskEntity task, long executionEnd) {
        AppLogger.getInstance().d(TAG, "Updating task " + task.getId() + " execution end time to " + executionEnd);
        task.setCurrentExecutionEnd(executionEnd);
        taskDao.updateExecutionEndTime(task.getId(), executionEnd, System.currentTimeMillis());
    }

    /**
     * 获取任务
     */
    public TaskEntity getTask(long taskId) {
        return taskDao.getTaskByIdSync(taskId);
    }

    /**
     * 获取所有正在执行的任务
     */
    public List<TaskEntity> getActiveTasks() {
        return taskDao.getActiveTasks();
    }

    // ==================== 并发控制相关方法 ====================

    /**
     * 获取并发管理器
     */
    public ConcurrencyManager getConcurrencyManager() {
        return concurrencyManager;
    }

    /**
     * 检查并发并尝试启动播放
     * 如果并发达上限，则设置状态为 WAITING_SLOT 并设置重试闹钟
     * 
     * 注意：此方法应该在 synchronized(getTaskLock(taskId)) 块中调用，以确保线程安全
     * 
     * @param task 任务实体
     * @param executionStart 执行开始时间戳
     * @param executionEnd 执行结束时间戳
     * @return true 如果成功启动播放，false 如果因并发限制进入等待
     */
    public boolean tryStartPlaybackWithConcurrencyCheck(TaskEntity task, 
            long executionStart, long executionEnd) {
        
        // 使用同步块确保并发检查和状态更新的原子性
        // 注意：这里使用类锁而非任务锁，因为需要跨任务保证并发数不超限
        synchronized (ConcurrencyManager.class) {
            // 检查并发数
            if (!concurrencyManager.canStartPlayback()) {
                int currentCount = concurrencyManager.getCurrentPlaybackCount();
                AppLogger.getInstance().w(TAG, "Concurrent playback limit reached (" + currentCount + "/" +
                        ConcurrencyManager.MAX_CONCURRENT_PLAYBACK + "), task " + 
                        task.getId() + " waiting for slot");
                
                // 更新状态为等待空位
                updateTaskExecutionInfo(task, TaskExecutionState.WAITING_SLOT, 
                        executionStart, executionEnd);
                
                // 设置重试闹钟（5分钟后）
                alarmScheduler.setRetryAlarm(task.getId());
                
                return false;
            }
            
            // 可以启动：先更新状态为 EXECUTING（这会增加数据库中的 EXECUTING 计数），再启动播放
            AppLogger.getInstance().d(TAG, "Concurrent check passed, starting playback for task " + task.getId());
            updateTaskExecutionInfo(task, TaskExecutionState.EXECUTING, 
                    executionStart, executionEnd);
        }
        
        // 状态已更新，可以在锁外启动播放
        startPlayback(task);
        return true;
    }

    /**
     * 处理重试启动闹钟
     * 
     * @param taskId 任务ID
     */
    public void handleRetryAlarm(long taskId) {
        AppLogger.getInstance().d(TAG, "Handling retry alarm for task " + taskId);
        
        synchronized (getTaskLock(taskId)) {
            TaskEntity task = taskDao.getTaskByIdSync(taskId);
            if (task == null) {
                AppLogger.getInstance().w(TAG, "Task " + taskId + " not found for retry");
                return;
            }

            if (!task.isEnabled()) {
                AppLogger.getInstance().d(TAG, "Task " + taskId + " is disabled, ignoring retry alarm");
                return;
            }

            TaskExecutionState currentState = task.getExecutionStateEnum();
            
            // 只有 WAITING_SLOT 状态才处理重试
            if (currentState != TaskExecutionState.WAITING_SLOT) {
                AppLogger.getInstance().d(TAG, "Task " + taskId + " state is " + currentState + ", skip retry");
                return;
            }
            
            // 检查是否已超过结束时间
            long now = System.currentTimeMillis();
            if (task.getCurrentExecutionEnd() > 0 && now >= task.getCurrentExecutionEnd()) {
                AppLogger.getInstance().d(TAG, "Task " + taskId + " execution window expired, marking as SKIPPED");
                handleSkipDueToConcurrency(task);
                return;
            }
            
            // 尝试启动
            TaskType type = TaskClassifier.classify(task);
            ScheduleStrategy strategy = strategies.get(type);
            if (strategy != null) {
                strategy.handleRetryStart(task, this);
            }
        }
    }

    /**
     * 处理因并发限制跳过的任务
     * 
     * 注意：SKIPPED 状态会保留，直到下次任务触发时才会更新
     * 这样用户可以在 UI 上看到"已跳过"状态
     */
    public void handleSkipDueToConcurrency(TaskEntity task) {
        AppLogger.getInstance().d(TAG, "Task " + task.getId() + " skipped due to concurrency limit timeout");
        
        // 取消重试闹钟
        alarmScheduler.cancelRetryAlarm(task.getId());
        
        // 更新状态为 SKIPPED（保留此状态供 UI 显示）
        updateTaskState(task, TaskExecutionState.SKIPPED);
        
        // 如果是重复任务，调度下一次（但不重置状态）
        // 下次开始闹钟触发时会更新状态
        if (!task.isOneTime()) {
            AppLogger.getInstance().d(TAG, "Repeat task " + task.getId() + ", scheduling next execution (keeping SKIPPED state)");
            TaskType type = TaskClassifier.classify(task);
            ScheduleStrategy strategy = strategies.get(type);
            if (strategy != null) {
                // 直接调用 schedule，不重置状态
                // schedule 方法会设置开始闹钟，但不会改变当前的 SKIPPED 状态
                // 因为 SKIPPED 任务仍然是启用状态
                scheduleNextForSkippedTask(task, strategy);
            }
        } else {
            // 一次性任务，禁用
            AppLogger.getInstance().d(TAG, "One-time task " + task.getId() + ", disabling");
            disableTask(task);
        }
    }

    /**
     * 为跳过的重复任务调度下一次执行
     * 只设置闹钟，不改变 SKIPPED 状态
     */
    private void scheduleNextForSkippedTask(TaskEntity task, ScheduleStrategy strategy) {
        // 计算下次执行时间并设置闹钟
        // 状态会在 handleStart 时更新
        long nextStartTime = TaskTimeCalculator.calculateNextStartTime(task);
        if (nextStartTime > 0) {
            AppLogger.getInstance().d(TAG, "Scheduling next start for skipped task " + task.getId() 
                    + " at " + new java.util.Date(nextStartTime));
            setStartAlarm(task.getId(), nextStartTime);
            
            // 同时设置结束闹钟
            long endTime = TaskTimeCalculator.calculateEndTimeForStart(task, nextStartTime);
            if (endTime > 0) {
                setEndAlarm(task.getId(), endTime);
            }
        }
    }

    /**
     * 设置重试闹钟
     */
    public void setRetryAlarm(long taskId) {
        alarmScheduler.setRetryAlarm(taskId);
    }

    /**
     * 取消重试闹钟
     */
    public void cancelRetryAlarm(long taskId) {
        alarmScheduler.cancelRetryAlarm(taskId);
    }
}
