# 设计文档 - 任务调度时间+重复逻辑重构

## 概述

本设计采用**状态机模型**统一管理任务的生命周期，通过**任务类型分类器**识别任务类型，使用**时间计算器**统一处理各种时间场景。核心思想是将分散的 if-else 逻辑收敛为清晰的状态转换和策略模式。

---

## 架构设计

### 系统架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                        调度触发层                                │
├─────────────┬─────────────┬─────────────┬─────────────────────────┤
│ AlarmReceiver│BootReceiver │TaskCheckWorker│ 用户操作(创建/修改/禁用) │
└──────┬──────┴──────┬──────┴──────┬──────┴───────────┬─────────────┘
       │             │             │                  │
       ▼             ▼             ▼                  ▼
┌─────────────────────────────────────────────────────────────────┐
│                    TaskScheduleManager (新)                      │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │                    核心调度引擎                              │ │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │ │
│  │  │TaskClassifier│  │TaskStateMachine│ │TaskTimeCalculator │  │ │
│  │  │ 任务分类器   │  │  任务状态机   │  │   时间计算器       │  │ │
│  │  └─────────────┘  └─────────────┘  └─────────────────────┘  │ │
│  └─────────────────────────────────────────────────────────────┘ │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │                    调度策略                                  │ │
│  │  ┌───────────┐ ┌───────────┐ ┌───────────┐ ┌───────────┐   │ │
│  │  │OneTimeTask│ │RepeatTask │ │AllDayTask │ │CrossDayTask│   │ │
│  │  │ Strategy  │ │ Strategy  │ │ Strategy  │ │ Strategy  │   │ │
│  │  └───────────┘ └───────────┘ └───────────┘ └───────────┘   │ │
│  └─────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
       │                                            │
       ▼                                            ▼
┌─────────────────┐                    ┌─────────────────────────┐
│  AlarmScheduler │                    │  AudioPlaybackService   │
│   闹钟调度器     │                    │     音频播放服务         │
└─────────────────┘                    └─────────────────────────┘
       │                                            │
       ▼                                            ▼
┌─────────────────┐                    ┌─────────────────────────┐
│  AlarmManager   │                    │     MediaPlayer         │
└─────────────────┘                    └─────────────────────────┘
```

### 模块职责

| 模块 | 职责 |
|------|------|
| TaskScheduleManager | 调度入口，协调各组件 |
| TaskClassifier | 根据任务属性识别任务类型 |
| TaskStateMachine | 管理任务状态转换 |
| TaskTimeCalculator | 统一的时间计算逻辑 |
| AlarmScheduler | 封装闹钟设置/取消逻辑 |
| ScheduleStrategy | 各类型任务的调度策略接口 |

---

## 详细设计

### 1. 任务状态机 (TaskStateMachine)

#### 状态定义

```java
public enum TaskExecutionState {
    IDLE,           // 空闲：任务未调度或已完成上一次执行
    SCHEDULED,      // 已调度：闹钟已设置，等待触发
    EXECUTING,      // 执行中：正在播放
    PAUSED,         // 暂停：蓝牙断开等原因暂停
    COMPLETED,      // 已完成：本次执行周期结束
    DISABLED        // 已禁用：任务被用户或系统禁用
}
```

#### 状态转换图

```
                    ┌─────────────────────────────────────┐
                    │                                     │
                    ▼                                     │
    ┌──────┐  schedule()  ┌───────────┐  startAlarm()  ┌──────────┐
    │ IDLE │─────────────▶│ SCHEDULED │───────────────▶│EXECUTING │
    └──────┘              └───────────┘                └──────────┘
        ▲                      │                           │  │
        │                      │ disable()                 │  │ pause()
        │                      ▼                           │  ▼
        │                 ┌──────────┐                 ┌────────┐
        │                 │ DISABLED │                 │ PAUSED │
        │                 └──────────┘                 └────────┘
        │                      │                           │
        │                      │ enable()                  │ resume()
        │                      ▼                           │
        │               重新进入 IDLE                       │
        │                                                  │
        │  ┌───────────┐  stopAlarm() / complete()         │
        └──│ COMPLETED │◀──────────────────────────────────┘
           └───────────┘
                │
                │ 一次性任务: disable()
                │ 重复任务: schedule() -> SCHEDULED
                ▼
```

#### 状态转换规则

| 当前状态 | 事件 | 目标状态 | 条件 |
|---------|------|---------|------|
| IDLE | schedule() | SCHEDULED | 任务启用且有有效的下次执行时间 |
| SCHEDULED | startAlarm() | EXECUTING | 当前时间在有效范围内 |
| SCHEDULED | disable() | DISABLED | 用户禁用任务 |
| EXECUTING | stopAlarm() | COMPLETED | 结束闹钟触发 |
| EXECUTING | pause() | PAUSED | 蓝牙断开等 |
| EXECUTING | disable() | DISABLED | 用户禁用任务 |
| PAUSED | resume() | EXECUTING | 蓝牙重连且时间仍有效 |
| PAUSED | stopAlarm() | COMPLETED | 结束时间已到 |
| COMPLETED | schedule() | SCHEDULED | 重复任务 |
| COMPLETED | disable() | DISABLED | 一次性任务自动禁用 |
| DISABLED | enable() | IDLE | 用户重新启用 |

### 2. 任务分类器 (TaskClassifier)

#### 任务类型枚举

```java
public enum TaskType {
    ONE_TIME_NORMAL,      // 一次性非跨天定时段
    ONE_TIME_CROSS_DAY,   // 一次性跨天定时段
    ONE_TIME_ALL_DAY,     // 一次性全天播放
    REPEAT_NORMAL,        // 重复非跨天定时段
    REPEAT_CROSS_DAY,     // 重复跨天定时段
    REPEAT_ALL_DAY,       // 重复全天播放
    EVERYDAY_NORMAL,      // 每天非跨天定时段
    EVERYDAY_CROSS_DAY    // 每天跨天定时段
}
```

#### 分类逻辑

```java
public class TaskClassifier {
    
    public static TaskType classify(TaskEntity task) {
        boolean isOneTime = task.getRepeatDays() == 0;
        boolean isEveryday = task.getRepeatDays() == TaskEntity.EVERYDAY;
        boolean isAllDay = task.isAllDayPlay();
        boolean isCrossDay = isCrossDayTask(task);
        
        if (isAllDay) {
            if (isOneTime) return ONE_TIME_ALL_DAY;
            return REPEAT_ALL_DAY;  // 每天全天等同于重复全天
        }
        
        if (isOneTime) {
            return isCrossDay ? ONE_TIME_CROSS_DAY : ONE_TIME_NORMAL;
        }
        
        if (isEveryday) {
            return isCrossDay ? EVERYDAY_CROSS_DAY : EVERYDAY_NORMAL;
        }
        
        return isCrossDay ? REPEAT_CROSS_DAY : REPEAT_NORMAL;
    }
    
    public static boolean isCrossDayTask(TaskEntity task) {
        if (task.isAllDayPlay()) return false;
        int startMinutes = parseTimeToMinutes(task.getStartTime());
        int endMinutes = parseTimeToMinutes(task.getEndTime());
        return endMinutes < startMinutes;
    }
}
```

### 3. 时间计算器 (TaskTimeCalculator)

#### 核心接口

```java
public class TaskTimeCalculator {
    
    /**
     * 判断任务当前是否应该处于活跃状态
     * @return TimeCheckResult 包含是否活跃及原因
     */
    public static TimeCheckResult shouldBeActiveNow(TaskEntity task) { ... }
    
    /**
     * 计算下一次开始时间
     * @return 下一次开始的时间戳，-1 表示无下次执行
     */
    public static long calculateNextStartTime(TaskEntity task) { ... }
    
    /**
     * 计算当前执行周期的结束时间
     * @return 结束时间戳
     */
    public static long calculateCurrentEndTime(TaskEntity task) { ... }
    
    /**
     * 判断指定日期是否在重复日中
     */
    public static boolean shouldExecuteOnDay(int repeatDays, Calendar day) { ... }
}
```

#### TimeCheckResult 结果类

```java
public class TimeCheckResult {
    private final boolean active;
    private final ActiveReason reason;
    private final long effectiveEndTime;  // 如果活跃，有效的结束时间
    
    public enum ActiveReason {
        IN_NORMAL_RANGE,           // 在正常时间范围内
        IN_CROSS_DAY_EVENING,      // 在跨天任务的晚间部分
        IN_CROSS_DAY_MORNING,      // 在跨天任务的凌晨部分
        ALL_DAY_ACTIVE,            // 全天播放且今天有效
        
        NOT_IN_RANGE,              // 不在时间范围内
        NOT_REPEAT_DAY,            // 今天/昨天不在重复日中
        ONE_TIME_MORNING_NO_STATE, // 一次性跨天凌晨，无执行状态
        TASK_DISABLED,             // 任务已禁用
        TASK_COMPLETED             // 一次性任务已完成
    }
}
```

#### shouldBeActiveNow 核心逻辑

```java
public static TimeCheckResult shouldBeActiveNow(TaskEntity task) {
    if (!task.isEnabled()) {
        return TimeCheckResult.inactive(TASK_DISABLED);
    }
    
    TaskType type = TaskClassifier.classify(task);
    Calendar now = Calendar.getInstance();
    int currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
    
    switch (type) {
        case ONE_TIME_ALL_DAY:
        case REPEAT_ALL_DAY:
            return checkAllDayActive(task, now);
            
        case ONE_TIME_NORMAL:
        case REPEAT_NORMAL:
        case EVERYDAY_NORMAL:
            return checkNormalTimeRange(task, now, currentMinutes);
            
        case ONE_TIME_CROSS_DAY:
        case REPEAT_CROSS_DAY:
        case EVERYDAY_CROSS_DAY:
            return checkCrossDayTimeRange(task, now, currentMinutes);
    }
}

private static TimeCheckResult checkCrossDayTimeRange(
        TaskEntity task, Calendar now, int currentMinutes) {
    
    int startMinutes = parseTimeToMinutes(task.getStartTime());
    int endMinutes = parseTimeToMinutes(task.getEndTime());
    TaskType type = TaskClassifier.classify(task);
    
    if (currentMinutes >= startMinutes) {
        // 晚间部分：检查今天是否在重复日中
        boolean todayValid = shouldExecuteOnDay(task.getRepeatDays(), now);
        if (todayValid) {
            long endTime = calculateTomorrowEndTime(now, endMinutes);
            return TimeCheckResult.active(IN_CROSS_DAY_EVENING, endTime);
        }
        return TimeCheckResult.inactive(NOT_REPEAT_DAY);
        
    } else if (currentMinutes < endMinutes) {
        // 凌晨部分：需要检查昨天是否在重复日中
        Calendar yesterday = (Calendar) now.clone();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        
        // 一次性任务的特殊处理：需要检查执行状态
        if (type == ONE_TIME_CROSS_DAY) {
            TaskExecutionState state = task.getExecutionState();
            if (state != TaskExecutionState.EXECUTING && 
                state != TaskExecutionState.PAUSED) {
                // 无执行状态，保守处理不恢复
                return TimeCheckResult.inactive(ONE_TIME_MORNING_NO_STATE);
            }
            // 有执行状态，恢复播放
            long endTime = calculateTodayEndTime(now, endMinutes);
            return TimeCheckResult.active(IN_CROSS_DAY_MORNING, endTime);
        }
        
        // 重复任务：检查昨天
        boolean yesterdayValid = shouldExecuteOnDay(task.getRepeatDays(), yesterday);
        if (yesterdayValid) {
            long endTime = calculateTodayEndTime(now, endMinutes);
            return TimeCheckResult.active(IN_CROSS_DAY_MORNING, endTime);
        }
        return TimeCheckResult.inactive(NOT_REPEAT_DAY);
        
    } else {
        // 白天部分：不在范围内
        return TimeCheckResult.inactive(NOT_IN_RANGE);
    }
}
```

### 4. 数据模型变更

#### TaskEntity 新增字段

```java
@Entity(tableName = "tasks")
public class TaskEntity {
    // ... 现有字段 ...
    
    /**
     * 执行状态
     * @see TaskExecutionState
     */
    @ColumnInfo(name = "execution_state")
    private int executionState = TaskExecutionState.IDLE.ordinal();
    
    /**
     * 当前执行周期的开始时间戳
     * 用于判断一次性跨天任务是否已开始执行
     */
    @ColumnInfo(name = "current_execution_start")
    private long currentExecutionStart = 0;
    
    /**
     * 当前执行周期的预期结束时间戳
     */
    @ColumnInfo(name = "current_execution_end")
    private long currentExecutionEnd = 0;
    
    // Getter/Setter
    public TaskExecutionState getExecutionState() {
        return TaskExecutionState.values()[executionState];
    }
    
    public void setExecutionState(TaskExecutionState state) {
        this.executionState = state.ordinal();
    }
}
```

#### 数据库迁移

```java
// Migration: 添加执行状态字段
public class Migration_AddExecutionState extends Migration {
    @Override
    public void migrate(@NonNull SupportSQLiteDatabase database) {
        database.execSQL(
            "ALTER TABLE tasks ADD COLUMN execution_state INTEGER NOT NULL DEFAULT 0"
        );
        database.execSQL(
            "ALTER TABLE tasks ADD COLUMN current_execution_start INTEGER NOT NULL DEFAULT 0"
        );
        database.execSQL(
            "ALTER TABLE tasks ADD COLUMN current_execution_end INTEGER NOT NULL DEFAULT 0"
        );
    }
}
```

### 5. 调度策略接口

```java
public interface ScheduleStrategy {
    
    /**
     * 调度任务
     * @return ScheduleResult 包含设置的闹钟信息
     */
    ScheduleResult schedule(TaskEntity task, TaskScheduleManager manager);
    
    /**
     * 处理开始闹钟触发
     */
    void handleStart(TaskEntity task, TaskScheduleManager manager);
    
    /**
     * 处理结束闹钟触发
     */
    void handleStop(TaskEntity task, TaskScheduleManager manager);
    
    /**
     * 处理设备重启后的恢复
     */
    void handleReboot(TaskEntity task, TaskScheduleManager manager);
}
```

#### 策略实现示例：一次性跨天任务

```java
public class OneTimeCrossDayStrategy implements ScheduleStrategy {
    
    @Override
    public ScheduleResult schedule(TaskEntity task, TaskScheduleManager manager) {
        TimeCheckResult checkResult = TaskTimeCalculator.shouldBeActiveNow(task);
        
        if (checkResult.isActive()) {
            // 当前应该活跃
            manager.startPlayback(task);
            manager.updateTaskState(task, EXECUTING);
            task.setCurrentExecutionStart(System.currentTimeMillis());
            task.setCurrentExecutionEnd(checkResult.getEffectiveEndTime());
            
            // 设置结束闹钟
            manager.setEndAlarm(task.getId(), checkResult.getEffectiveEndTime());
            
            return ScheduleResult.immediate(checkResult.getEffectiveEndTime());
        }
        
        // 不活跃，检查原因
        switch (checkResult.getReason()) {
            case ONE_TIME_MORNING_NO_STATE:
                // 凌晨部分但无执行状态，设置今天的结束闹钟然后禁用
                long todayEndTime = TaskTimeCalculator.calculateTodayEndTime(task);
                manager.setEndAlarm(task.getId(), todayEndTime);
                // 不设置开始闹钟
                return ScheduleResult.endOnly(todayEndTime);
                
            case NOT_IN_RANGE:
                // 计算开始时间
                long startTime = TaskTimeCalculator.calculateNextStartTime(task);
                if (startTime < 0) {
                    // 无有效开始时间（时间已过）
                    manager.updateTaskState(task, DISABLED);
                    return ScheduleResult.noSchedule();
                }
                long endTime = TaskTimeCalculator.calculateEndTimeForStart(task, startTime);
                manager.setStartAlarm(task.getId(), startTime);
                manager.setEndAlarm(task.getId(), endTime);
                manager.updateTaskState(task, SCHEDULED);
                return ScheduleResult.scheduled(startTime, endTime);
                
            default:
                return ScheduleResult.noSchedule();
        }
    }
    
    @Override
    public void handleStart(TaskEntity task, TaskScheduleManager manager) {
        // 二次验证
        TimeCheckResult checkResult = TaskTimeCalculator.shouldBeActiveNow(task);
        if (!checkResult.isActive()) {
            Log.w(TAG, "Start alarm triggered but task should not be active: " 
                + checkResult.getReason());
            return;
        }
        
        manager.startPlayback(task);
        manager.updateTaskState(task, EXECUTING);
        task.setCurrentExecutionStart(System.currentTimeMillis());
        task.setCurrentExecutionEnd(checkResult.getEffectiveEndTime());
        manager.saveTask(task);
    }
    
    @Override
    public void handleStop(TaskEntity task, TaskScheduleManager manager) {
        manager.stopPlayback(task);
        manager.updateTaskState(task, COMPLETED);
        
        // 一次性任务完成后禁用
        manager.disableTask(task);
    }
    
    @Override
    public void handleReboot(TaskEntity task, TaskScheduleManager manager) {
        TimeCheckResult checkResult = TaskTimeCalculator.shouldBeActiveNow(task);
        
        if (checkResult.isActive()) {
            // 检查执行状态
            if (task.getExecutionState() == EXECUTING || 
                task.getExecutionState() == PAUSED) {
                // 恢复播放
                manager.startPlayback(task);
                manager.setEndAlarm(task.getId(), checkResult.getEffectiveEndTime());
            }
            // 如果状态不是 EXECUTING/PAUSED，说明从未开始或已完成，不恢复
        } else {
            // 重新调度
            schedule(task, manager);
        }
    }
}
```

### 6. TaskScheduleManager 主类

```java
public class TaskScheduleManager {
    private static TaskScheduleManager instance;
    private final Context context;
    private final TaskDao taskDao;
    private final AlarmScheduler alarmScheduler;
    private final Map<TaskType, ScheduleStrategy> strategies;
    
    private TaskScheduleManager(Context context) {
        this.context = context.getApplicationContext();
        this.taskDao = AppDatabase.getInstance(context).taskDao();
        this.alarmScheduler = new AlarmScheduler(context);
        this.strategies = initStrategies();
    }
    
    private Map<TaskType, ScheduleStrategy> initStrategies() {
        Map<TaskType, ScheduleStrategy> map = new EnumMap<>(TaskType.class);
        
        // 一次性任务策略
        map.put(ONE_TIME_NORMAL, new OneTimeNormalStrategy());
        map.put(ONE_TIME_CROSS_DAY, new OneTimeCrossDayStrategy());
        map.put(ONE_TIME_ALL_DAY, new OneTimeAllDayStrategy());
        
        // 重复任务策略
        map.put(REPEAT_NORMAL, new RepeatNormalStrategy());
        map.put(REPEAT_CROSS_DAY, new RepeatCrossDayStrategy());
        map.put(REPEAT_ALL_DAY, new RepeatAllDayStrategy());
        
        // 每天任务（复用重复任务策略）
        map.put(EVERYDAY_NORMAL, new RepeatNormalStrategy());
        map.put(EVERYDAY_CROSS_DAY, new RepeatCrossDayStrategy());
        
        return map;
    }
    
    /**
     * 调度单个任务
     */
    public void scheduleTask(TaskEntity task) {
        if (!task.isEnabled()) {
            cancelTask(task);
            return;
        }
        
        TaskType type = TaskClassifier.classify(task);
        ScheduleStrategy strategy = strategies.get(type);
        
        Log.d(TAG, "Scheduling task " + task.getId() + " with type " + type);
        strategy.schedule(task, this);
    }
    
    /**
     * 处理开始闹钟
     */
    public void handleStartAlarm(long taskId) {
        TaskEntity task = taskDao.getTaskByIdSync(taskId);
        if (task == null || !task.isEnabled()) return;
        
        TaskType type = TaskClassifier.classify(task);
        strategies.get(type).handleStart(task, this);
    }
    
    /**
     * 处理结束闹钟
     */
    public void handleStopAlarm(long taskId) {
        TaskEntity task = taskDao.getTaskByIdSync(taskId);
        if (task == null) return;
        
        TaskType type = TaskClassifier.classify(task);
        strategies.get(type).handleStop(task, this);
    }
    
    /**
     * 重新调度所有任务（设备重启后调用）
     */
    public void rescheduleAllTasks() {
        List<TaskEntity> tasks = taskDao.getEnabledTasksSync();
        for (TaskEntity task : tasks) {
            TaskType type = TaskClassifier.classify(task);
            strategies.get(type).handleReboot(task, this);
        }
    }
    
    // 辅助方法
    public void startPlayback(TaskEntity task) {
        AudioPlaybackService.startTaskPlayback(context, task.getId());
    }
    
    public void stopPlayback(TaskEntity task) {
        AudioPlaybackService.stopTaskPlayback(context, task.getId());
    }
    
    public void updateTaskState(TaskEntity task, TaskExecutionState state) {
        task.setExecutionState(state);
        taskDao.updateExecutionState(task.getId(), state.ordinal(), 
            System.currentTimeMillis());
    }
    
    public void disableTask(TaskEntity task) {
        task.setEnabled(false);
        task.setExecutionState(TaskExecutionState.DISABLED);
        taskDao.updateEnabled(task.getId(), false, System.currentTimeMillis());
        alarmScheduler.cancelAlarms(task.getId());
    }
    
    public void setStartAlarm(long taskId, long triggerTime) {
        alarmScheduler.setStartAlarm(taskId, triggerTime);
    }
    
    public void setEndAlarm(long taskId, long triggerTime) {
        alarmScheduler.setEndAlarm(taskId, triggerTime);
    }
}
```

### 7. 闹钟调度器 (AlarmScheduler)

```java
public class AlarmScheduler {
    private static final int REQUEST_CODE_START_PREFIX = 10000;
    private static final int REQUEST_CODE_STOP_PREFIX = 20000;
    
    private final Context context;
    private final AlarmManager alarmManager;
    
    public AlarmScheduler(Context context) {
        this.context = context;
        this.alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    }
    
    /**
     * 设置开始闹钟（使用 AlarmClock，最高优先级）
     */
    public void setStartAlarm(long taskId, long triggerTime) {
        PendingIntent pi = createPendingIntent(taskId, true);
        
        // 使用 AlarmClockInfo 确保 Doze 模式下也能触发
        AlarmManager.AlarmClockInfo info = new AlarmManager.AlarmClockInfo(
            triggerTime, pi);
        alarmManager.setAlarmClock(info, pi);
        
        Log.d(TAG, "Set start alarm for task " + taskId + 
            " at " + new Date(triggerTime));
    }
    
    /**
     * 设置结束闹钟（精确闹钟）
     */
    public void setEndAlarm(long taskId, long triggerTime) {
        PendingIntent pi = createPendingIntent(taskId, false);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerTime, pi);
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerTime, pi);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerTime, pi);
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pi);
        }
        
        Log.d(TAG, "Set end alarm for task " + taskId + 
            " at " + new Date(triggerTime));
    }
    
    /**
     * 取消任务的所有闹钟
     */
    public void cancelAlarms(long taskId) {
        alarmManager.cancel(createPendingIntent(taskId, true));
        alarmManager.cancel(createPendingIntent(taskId, false));
        Log.d(TAG, "Cancelled alarms for task " + taskId);
    }
    
    /**
     * 只取消开始闹钟
     */
    public void cancelStartAlarm(long taskId) {
        alarmManager.cancel(createPendingIntent(taskId, true));
    }
    
    /**
     * 只取消结束闹钟
     */
    public void cancelEndAlarm(long taskId) {
        alarmManager.cancel(createPendingIntent(taskId, false));
    }
    
    private PendingIntent createPendingIntent(long taskId, boolean isStart) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.setAction(isStart ? 
            AlarmReceiver.ACTION_TASK_START : AlarmReceiver.ACTION_TASK_STOP);
        intent.putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId);
        
        int requestCode = isStart ? 
            REQUEST_CODE_START_PREFIX + (Long.hashCode(taskId) & 0x7FFF) :
            REQUEST_CODE_STOP_PREFIX + (Long.hashCode(taskId) & 0x7FFF);
            
        return PendingIntent.getBroadcast(context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
```

---

## 类图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           TaskScheduleManager                            │
├─────────────────────────────────────────────────────────────────────────┤
│ - context: Context                                                       │
│ - taskDao: TaskDao                                                       │
│ - alarmScheduler: AlarmScheduler                                         │
│ - strategies: Map<TaskType, ScheduleStrategy>                            │
├─────────────────────────────────────────────────────────────────────────┤
│ + scheduleTask(task: TaskEntity): void                                   │
│ + handleStartAlarm(taskId: long): void                                   │
│ + handleStopAlarm(taskId: long): void                                    │
│ + rescheduleAllTasks(): void                                             │
│ + startPlayback(task: TaskEntity): void                                  │
│ + stopPlayback(task: TaskEntity): void                                   │
│ + updateTaskState(task: TaskEntity, state: TaskExecutionState): void     │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                    ┌───────────────┼───────────────┐
                    │               │               │
                    ▼               ▼               ▼
        ┌───────────────┐  ┌───────────────┐  ┌───────────────┐
        │TaskClassifier │  │AlarmScheduler │  │ScheduleStrategy│
        └───────────────┘  └───────────────┘  └───────┬───────┘
                                                      │
                    ┌─────────────┬─────────────┬─────┴────────┐
                    │             │             │              │
                    ▼             ▼             ▼              ▼
        ┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐
        │OneTimeNormal    │ │OneTimeCrossDay  │ │RepeatNormal     │
        │Strategy         │ │Strategy         │ │Strategy         │ ...
        └─────────────────┘ └─────────────────┘ └─────────────────┘
```

---

## 文件结构

```
app/src/main/java/com/caleb/scheduledplayer/
├── data/
│   └── entity/
│       └── TaskEntity.java          # 修改：添加执行状态字段
│
└── service/
    └── scheduler/
        ├── TaskScheduleManager.java      # 新增：调度管理器
        ├── TaskClassifier.java           # 新增：任务分类器
        ├── TaskStateMachine.java         # 新增：状态机
        ├── TaskTimeCalculator.java       # 新增：时间计算器
        ├── TimeCheckResult.java          # 新增：时间检查结果
        ├── AlarmScheduler.java           # 新增：闹钟调度器
        ├── ScheduleStrategy.java         # 新增：策略接口
        ├── ScheduleResult.java           # 新增：调度结果
        ├── strategy/
        │   ├── OneTimeNormalStrategy.java
        │   ├── OneTimeCrossDayStrategy.java
        │   ├── OneTimeAllDayStrategy.java
        │   ├── RepeatNormalStrategy.java
        │   ├── RepeatCrossDayStrategy.java
        │   └── RepeatAllDayStrategy.java
        ├── TaskSchedulerService.java     # 修改：简化为调用 TaskScheduleManager
        ├── AlarmReceiver.java            # 修改：简化为调用 TaskScheduleManager
        └── BootReceiver.java             # 修改：简化为调用 TaskScheduleManager
```

---

## 测试策略

### 单元测试

| 测试类 | 测试内容 |
|--------|---------|
| TaskClassifierTest | 所有任务类型的正确分类 |
| TaskTimeCalculatorTest | 各种时间场景的计算正确性 |
| TaskStateMachineTest | 状态转换的正确性 |
| *StrategyTest | 各策略的调度逻辑 |

### 集成测试

| 测试场景 | 验证内容 |
|---------|---------|
| 一次性任务完整流程 | 创建→调度→执行→禁用 |
| 跨天任务晚间启动 | 正确设置明天的结束闹钟 |
| 跨天任务凌晨恢复 | 根据执行状态决定是否恢复 |
| 设备重启恢复 | 正确恢复执行中的任务 |
| 任务修改 | 正确重新调度 |

### 边界测试

| 边界场景 | 验证内容 |
|---------|---------|
| 23:59 创建 00:01 结束的任务 | 正确识别跨天 |
| 午夜时刻的状态判断 | 00:00:00 的正确处理 |
| 周日到周一的跨天 | 正确判断昨天 |

---

## 安全考虑

1. **数据一致性**：状态更新必须与数据库操作在同一事务中
2. **并发安全**：使用 synchronized 或 ReentrantLock 保护状态更新
3. **异常处理**：所有闹钟回调中捕获异常，避免崩溃

---

## 迁移策略

1. **数据库迁移**：添加新字段，旧任务默认状态为 IDLE
2. **渐进式替换**：保留旧的 TaskSchedulerService 接口，内部委托给新模块
3. **回滚机制**：新旧代码可通过 Feature Flag 切换

