# 实施计划 - 多任务并发播放限制

## 实施概览

预计总工时：6-8 小时
关键里程碑：
1. 状态枚举和数据库层完成
2. 并发控制核心逻辑完成
3. 重试机制完成
4. UI 显示完成

## 任务列表

### 1. 状态枚举扩展

- [ ] 1.1 扩展 TaskExecutionState 枚举
  - 新增 `SKIPPED(6, "已跳过")` 状态
  - 新增 `WAITING_SLOT(7, "等待空位")` 状态
  - 更新 `canTransitionTo()` 方法支持新状态转换
  - 更新 `fromValue()` 方法处理新值
  - _需求：FR-2, FR-4_

### 2. 数据库层

- [ ] 2.1 TaskDao 新增并发计数查询方法
  - 添加 `getExecutingTaskCount()` 同步方法
  - 添加 `getExecutingTaskCountLive()` LiveData 方法
  - _需求：FR-1_

- [ ] 2.2 TaskDao 新增状态查询方法
  - 添加 `getTasksByState(int state)` 查询指定状态的任务
  - 添加 `getWaitingSlotTasks()` 查询等待空位的任务
  - _需求：FR-2_

### 3. 并发控制核心

- [ ] 3.1 创建 ConcurrencyManager 类
  - 定义 `MAX_CONCURRENT_PLAYBACK = 10` 常量
  - 实现 `canStartPlayback()` 方法（基于数据库查询）
  - 实现 `getCurrentPlaybackCount()` 方法
  - 实现 `getAvailableSlots()` 方法
  - _需求：FR-1_

- [ ] 3.2 TaskScheduleManager 集成并发检查
  - 注入 ConcurrencyManager 依赖
  - 新增 `tryStartPlaybackWithConcurrencyCheck()` 方法
  - 新增 `handleRetryStart()` 方法
  - 新增 `handleSkipDueToConcurrency()` 方法
  - _需求：FR-1, FR-2_

### 4. 重试闹钟机制

- [ ] 4.1 AlarmScheduler 扩展重试闹钟
  - 新增 `ACTION_RETRY_START` 常量
  - 新增 `RETRY_INTERVAL_MS = 5 * 60 * 1000` 常量
  - 实现 `setRetryAlarm(long taskId)` 方法
  - 实现 `cancelRetryAlarm(long taskId)` 方法
  - 实现 `calculateRetryRequestCode()` 方法
  - _需求：FR-2_

- [ ] 4.2 AlarmReceiver 处理重试闹钟
  - 在 `onReceive()` 中添加 `ACTION_RETRY_START` 处理分支
  - 实现 `handleRetryStart()` 方法
  - _需求：FR-2_

### 5. 策略层修改

- [ ] 5.1 BaseScheduleStrategy 修改启动逻辑
  - 修改 `startPlaybackAndUpdateState()` 使用并发检查
  - 新增 `handleRetryStart()` 默认实现
  - _需求：FR-1, FR-2_

- [ ] 5.2 ScheduleStrategy 接口扩展
  - 新增 `handleRetryStart(TaskEntity task, TaskScheduleManager manager)` 方法
  - _需求：FR-2_

### 6. 日志记录

- [ ] 6.1 TaskLogRepository 扩展
  - 新增日志类型：`LOG_TYPE_WAITING`（等待空位）
  - 新增日志类型：`LOG_TYPE_SKIPPED`（已跳过）
  - 实现 `createWaitingLog()` 方法
  - 实现 `createSkippedLog()` 方法
  - 实现 `updateLogRetrySuccess()` 方法（等待后成功启动）
  - _需求：FR-3_

### 7. UI 显示

- [ ] 7.1 任务列表状态显示
  - 修改 TaskAdapter/ViewHolder 处理 SKIPPED 状态
  - 修改 TaskAdapter/ViewHolder 处理 WAITING_SLOT 状态
  - 添加状态对应的颜色和文字
  - _需求：FR-4_

- [ ] 7.2 任务详情/日志显示跳过原因
  - 在任务日志列表中显示"并发限制"原因
  - _需求：FR-4_

### 8. 边界情况处理

- [ ] 8.1 设备重启恢复
  - 修改 `BootReceiver` 处理 WAITING_SLOT 状态任务
  - 重启后重新设置重试闹钟或立即重试
  - _需求：FR-2_

- [ ] 8.2 任务编辑/禁用时取消重试
  - 在任务禁用时调用 `cancelRetryAlarm()`
  - 在任务编辑保存时重置状态并取消重试闹钟
  - _需求：FR-2_

### 9. 测试验证

- [ ] 9.1 功能测试
  - 测试 10 个任务同时播放
  - 测试第 11 个任务被拒绝并进入等待
  - 测试停止一个任务后等待任务能启动
  - 测试重试超时后任务标记为 SKIPPED
  - _需求：所有_

## 依赖关系

```
1.1 (枚举) 
    ↓
2.1, 2.2 (数据库) 
    ↓
3.1 (ConcurrencyManager) → 3.2 (TaskScheduleManager)
    ↓
4.1 (AlarmScheduler) → 4.2 (AlarmReceiver)
    ↓
5.1, 5.2 (策略层)
    ↓
6.1 (日志) + 7.1, 7.2 (UI)
    ↓
8.1, 8.2 (边界处理)
    ↓
9.1 (测试)
```

## 实施顺序建议

1. **第一阶段**（核心功能）：1.1 → 2.1 → 3.1 → 3.2 → 5.1
2. **第二阶段**（重试机制）：4.1 → 4.2 → 5.2
3. **第三阶段**（完善）：2.2 → 6.1 → 7.1 → 7.2
4. **第四阶段**（收尾）：8.1 → 8.2 → 9.1

