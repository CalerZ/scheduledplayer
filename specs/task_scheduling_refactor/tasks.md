# 实施计划 - 任务调度时间+重复逻辑重构

## 实施概览

- **预计总工时**：16-20 小时
- **关键里程碑**：
  1. 基础模型层完成（任务 1-2）
  2. 核心调度引擎完成（任务 3-5）
  3. 策略实现完成（任务 6）
  4. 集成与迁移完成（任务 7-8）

## 实施原则

1. **增量式交付**：每个任务完成后代码可编译运行
2. **向后兼容**：保留旧接口，内部委托给新模块
3. **可回滚**：通过简单修改可切换回旧逻辑

---

## 任务列表

### 1. 数据模型层

- [ ] **1.1 添加执行状态枚举类**
  - 创建 `TaskExecutionState.java` 枚举
  - 包含：IDLE, SCHEDULED, EXECUTING, PAUSED, COMPLETED, DISABLED
  - 添加状态描述和转换验证方法
  - _需求：FR-1 验收标准 1-5_

- [ ] **1.2 修改 TaskEntity 添加新字段**
  - 添加 `executionState` 字段（int，映射枚举）
  - 添加 `currentExecutionStart` 字段（long，执行开始时间戳）
  - 添加 `currentExecutionEnd` 字段（long，执行结束时间戳）
  - 添加 getter/setter 方法
  - _需求：FR-1 验收标准 1-5_

- [ ] **1.3 数据库迁移**
  - 创建 Migration 类添加三个新字段
  - 更新 AppDatabase 版本号和 Migration 配置
  - 旧数据默认状态为 IDLE
  - _需求：FR-1 验收标准 1_

- [ ] **1.4 更新 TaskDao**
  - 添加 `updateExecutionState(taskId, state, timestamp)` 方法
  - 添加 `updateExecutionTimes(taskId, start, end)` 方法
  - 添加 `getTasksByState(state)` 查询方法
  - _需求：FR-1 验收标准 2-5_

### 2. 核心工具类

- [ ] **2.1 创建任务分类器 TaskClassifier**
  - 创建 `TaskType` 枚举（8 种类型）
  - 实现 `classify(TaskEntity)` 方法
  - 实现 `isCrossDayTask(TaskEntity)` 方法
  - 添加单元测试验证分类正确性
  - _需求：所有 FR 的前置条件_

- [ ] **2.2 创建时间检查结果类 TimeCheckResult**
  - 创建 `ActiveReason` 枚举
  - 实现 `TimeCheckResult` 类
  - 包含：active, reason, effectiveEndTime
  - 提供静态工厂方法
  - _需求：FR-2 到 FR-7 的时间判断_

- [ ] **2.3 创建时间计算器 TaskTimeCalculator**
  - 实现 `shouldBeActiveNow(TaskEntity)` 方法
  - 实现 `calculateNextStartTime(TaskEntity)` 方法
  - 实现 `calculateCurrentEndTime(TaskEntity)` 方法
  - 实现 `shouldExecuteOnDay(repeatDays, Calendar)` 方法
  - 实现 `parseTimeToMinutes(String)` 辅助方法
  - 添加单元测试覆盖各种时间场景
  - _需求：FR-2 到 FR-7 验收标准_

### 3. 闹钟调度器

- [ ] **3.1 创建 AlarmScheduler 类**
  - 实现 `setStartAlarm(taskId, triggerTime)` 方法
  - 实现 `setEndAlarm(taskId, triggerTime)` 方法
  - 实现 `cancelAlarms(taskId)` 方法
  - 实现 `cancelStartAlarm(taskId)` / `cancelEndAlarm(taskId)` 方法
  - 封装 PendingIntent 创建逻辑
  - 处理不同 Android 版本的 API 差异
  - _需求：NFR-1 可靠性_

- [ ] **3.2 创建 ScheduleResult 类**
  - 定义调度结果类型：IMMEDIATE, SCHEDULED, END_ONLY, NO_SCHEDULE
  - 包含：startTime, endTime, resultType
  - 提供静态工厂方法
  - _需求：所有 FR 的调度结果_

### 4. 策略接口和基类

- [ ] **4.1 创建 ScheduleStrategy 接口**
  - 定义 `schedule(task, manager)` 方法
  - 定义 `handleStart(task, manager)` 方法
  - 定义 `handleStop(task, manager)` 方法
  - 定义 `handleReboot(task, manager)` 方法
  - _需求：所有 FR_

- [ ] **4.2 创建 BaseScheduleStrategy 抽象类**
  - 实现通用的状态更新逻辑
  - 实现通用的日志记录
  - 实现通用的错误处理
  - _需求：NFR-3 可维护性_

### 5. 调度管理器

- [ ] **5.1 创建 TaskScheduleManager 主类**
  - 实现单例模式
  - 初始化所有策略实例
  - 注入 TaskDao、AlarmScheduler 依赖
  - _需求：所有 FR_

- [ ] **5.2 实现核心调度方法**
  - 实现 `scheduleTask(TaskEntity)` 方法
  - 实现 `handleStartAlarm(taskId)` 方法
  - 实现 `handleStopAlarm(taskId)` 方法
  - 实现 `rescheduleAllTasks()` 方法
  - _需求：FR-8, FR-9_

- [ ] **5.3 实现辅助方法**
  - 实现 `startPlayback(task)` 委托 AudioPlaybackService
  - 实现 `stopPlayback(task)` 委托 AudioPlaybackService
  - 实现 `updateTaskState(task, state)` 更新数据库
  - 实现 `disableTask(task)` 禁用任务
  - 实现 `cancelTask(task)` 取消调度
  - _需求：FR-1 到 FR-9_

### 6. 策略实现

- [ ] **6.1 实现 OneTimeNormalStrategy**
  - 处理一次性非跨天任务的调度逻辑
  - 开始时验证时间有效性
  - 结束时禁用任务
  - 重启时检查执行状态
  - _需求：FR-2_

- [ ] **6.2 实现 OneTimeCrossDayStrategy**
  - 处理一次性跨天任务的调度逻辑
  - 晚间部分：设置明天的结束闹钟
  - 凌晨部分：检查执行状态决定是否恢复
  - 结束时禁用任务
  - _需求：FR-3_

- [ ] **6.3 实现 OneTimeAllDayStrategy**
  - 处理一次性全天播放任务
  - 当天需要执行时立即开始
  - 设置午夜检查闹钟
  - 午夜后停止并禁用
  - _需求：FR-4_

- [ ] **6.4 实现 RepeatNormalStrategy**
  - 处理重复非跨天任务
  - 检查今天是否在重复日
  - 结束后调度下一次执行
  - 重启时根据时间和重复日恢复
  - _需求：FR-5_

- [ ] **6.5 实现 RepeatCrossDayStrategy**
  - 处理重复跨天任务
  - 晚间检查今天，凌晨检查昨天
  - 结束后调度下一次执行
  - 重启时正确判断是否恢复
  - _需求：FR-6_

- [ ] **6.6 实现 RepeatAllDayStrategy**
  - 处理重复全天播放任务
  - 午夜检查明天是否继续
  - 新的一天开始时判断是否启动
  - _需求：FR-7_

### 7. 集成与迁移

- [ ] **7.1 修改 AlarmReceiver**
  - 简化 `handleTaskStart` 为调用 `TaskScheduleManager.handleStartAlarm`
  - 简化 `handleTaskStop` 为调用 `TaskScheduleManager.handleStopAlarm`
  - 保留 Action 常量和 Intent 解析逻辑
  - _需求：所有 FR_

- [ ] **7.2 修改 BootReceiver**
  - 简化为调用 `TaskScheduleManager.rescheduleAllTasks()`
  - 保留启动广播过滤逻辑
  - _需求：FR-8_

- [ ] **7.3 修改 TaskCheckWorker**
  - 使用 `TaskScheduleManager` 的方法检查和恢复任务
  - 简化现有的复杂判断逻辑
  - _需求：FR-9_

- [ ] **7.4 修改 TaskSchedulerService**
  - 保留公开接口不变
  - 内部委托给 `TaskScheduleManager`
  - 标记旧方法为 @Deprecated（可选）
  - _需求：向后兼容_

- [ ] **7.5 修改任务创建/编辑流程**
  - 创建任务时初始化执行状态为 IDLE
  - 编辑任务时重置执行状态
  - 禁用任务时更新状态为 DISABLED
  - 启用任务时重置状态为 IDLE 并重新调度
  - _需求：FR-1_

### 8. 测试与验证

- [ ] **8.1 编译验证**
  - 确保所有代码编译通过
  - 修复任何 lint 警告
  - _需求：NFR-3_

- [ ] **8.2 功能测试**
  - 测试一次性非跨天任务完整流程
  - 测试一次性跨天任务完整流程
  - 测试重复任务完整流程
  - 测试全天播放任务完整流程
  - 测试设备重启恢复
  - _需求：所有 FR_

- [ ] **8.3 边界测试**
  - 测试午夜边界场景
  - 测试周日到周一跨天
  - 测试用户在执行中修改任务
  - 测试用户在执行中禁用任务
  - _需求：边界条件处理_

---

## 文件创建/修改清单

### 新建文件（12 个）

| 文件路径 | 描述 |
|---------|------|
| `scheduler/TaskExecutionState.java` | 执行状态枚举 |
| `scheduler/TaskType.java` | 任务类型枚举 |
| `scheduler/TaskClassifier.java` | 任务分类器 |
| `scheduler/TimeCheckResult.java` | 时间检查结果 |
| `scheduler/TaskTimeCalculator.java` | 时间计算器 |
| `scheduler/AlarmScheduler.java` | 闹钟调度器 |
| `scheduler/ScheduleResult.java` | 调度结果 |
| `scheduler/ScheduleStrategy.java` | 策略接口 |
| `scheduler/BaseScheduleStrategy.java` | 策略基类 |
| `scheduler/TaskScheduleManager.java` | 调度管理器 |
| `scheduler/strategy/*.java` | 6 个策略实现类 |

### 修改文件（7 个）

| 文件路径 | 修改内容 |
|---------|---------|
| `entity/TaskEntity.java` | 添加 3 个新字段 |
| `dao/TaskDao.java` | 添加状态更新方法 |
| `database/AppDatabase.java` | 添加 Migration |
| `scheduler/AlarmReceiver.java` | 简化为委托调用 |
| `scheduler/BootReceiver.java` | 简化为委托调用 |
| `scheduler/TaskSchedulerService.java` | 委托给新管理器 |
| `worker/TaskCheckWorker.java` | 使用新管理器 |

---

## 依赖关系图

```
任务 1.1 ─┐
任务 1.2 ─┼─► 任务 1.3 ─► 任务 1.4
          │
任务 2.1 ─┤
任务 2.2 ─┼─► 任务 2.3
          │
          └─► 任务 3.1 ─► 任务 3.2
                  │
                  ▼
          任务 4.1 ─► 任务 4.2
                  │
                  ▼
          任务 5.1 ─► 任务 5.2 ─► 任务 5.3
                          │
                          ▼
                    任务 6.1 ~ 6.6
                          │
                          ▼
                    任务 7.1 ~ 7.5
                          │
                          ▼
                    任务 8.1 ~ 8.3
```

---

## 风险与应对

| 风险 | 可能性 | 影响 | 应对措施 |
|------|-------|------|---------|
| 数据库迁移失败 | 低 | 高 | 使用 fallbackToDestructiveMigration 作为开发期备选 |
| 策略实现遗漏边界情况 | 中 | 中 | 详细的测试用例覆盖 |
| 新旧逻辑切换导致状态不一致 | 中 | 中 | 迁移时重置所有任务状态为 IDLE |
| 性能下降（策略模式开销） | 低 | 低 | 策略实例复用，避免频繁创建 |

---

## 建议执行顺序

1. **第一阶段**（任务 1-2）：基础层，可独立测试
2. **第二阶段**（任务 3-5）：核心引擎，需要依赖第一阶段
3. **第三阶段**（任务 6）：策略实现，可并行开发
4. **第四阶段**（任务 7-8）：集成迁移，需要前三阶段完成

建议每个阶段完成后进行编译验证和基本测试。

