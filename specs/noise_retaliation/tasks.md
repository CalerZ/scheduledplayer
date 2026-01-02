# 实施计划 - 噪音反击 (Noise Retaliation)

## 实施概览

**预计总工时**：60-70 小时  
**开发语言**：Java  
**目标平台**：Android 8.0+  
**主要目标设备**：华为手机  

### 关键里程碑
1. 项目基础搭建与数据层实现
2. 核心服务层实现（播放服务、调度服务）
3. UI 层实现（任务管理界面）
4. **任务执行日志功能**（新增）
5. 华为设备后台保活适配
6. 模拟器功能测试与自动化测试
7. 华为真机验证测试

---

## 任务列表

### 阶段 1：项目基础搭建（预计 4 小时）

- [ ] **1.1 创建 Android 项目**
  - 使用 Android Studio 创建新项目
  - 配置 build.gradle：minSdk 26, targetSdk 34
  - 添加依赖：Room、Lifecycle、Material Design
  - _需求：项目基础_

- [ ] **1.2 配置 AndroidManifest.xml**
  - 添加权限声明：
    - `READ_EXTERNAL_STORAGE` / `READ_MEDIA_AUDIO`
    - `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK`
    - `RECEIVE_BOOT_COMPLETED`
    - `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`
    - `POST_NOTIFICATIONS`
    - `WAKE_LOCK`
  - 注册 Service、Receiver 组件
  - _需求：FR-4 后台播放、FR-3 定时播放_

---

### 阶段 2：数据层实现（预计 6 小时）

- [ ] **2.1 创建 Task 实体类**
  - 文件：`data/entity/Task.java`
  - 字段：id, name, startTime, endTime, playMode, repeatType, repeatDays, isEnabled, createdAt
  - Room 注解配置
  - _需求：FR-1 任务创建_

- [ ] **2.2 创建 TaskAudioFile 实体类**
  - 文件：`data/entity/TaskAudioFile.java`
  - 字段：id, taskId, filePath, fileName, sortOrder
  - 外键关联 Task
  - _需求：FR-1 音频选择_

- [ ] **2.3 创建 TaskWithAudioFiles 关系类**
  - 文件：`data/entity/TaskWithAudioFiles.java`
  - 使用 @Relation 注解定义一对多关系
  - _需求：FR-1 任务与音频关联_

- [ ] **2.4 创建 TaskDao 接口**
  - 文件：`data/dao/TaskDao.java`
  - 方法：insert, update, delete, getAll, getById, getEnabledTasks, getTaskWithAudioFiles
  - _需求：FR-2 任务管理_

- [ ] **2.5 创建 AppDatabase 类**
  - 文件：`data/AppDatabase.java`
  - 配置 Room 数据库
  - 单例模式实现
  - _需求：数据持久化_

- [ ] **2.6 创建 TaskRepository 类**
  - 文件：`data/repository/TaskRepository.java`
  - 封装数据库操作
  - 提供 LiveData 支持
  - _需求：FR-2 任务管理_

---

### 阶段 3：核心服务层实现（预计 14 小时）

- [ ] **3.1 创建 PlaybackService 前台服务**
  - 文件：`service/PlaybackService.java`
  - 继承 Service，实现前台服务
  - 创建通知渠道和通知
  - 实现 startForeground() 保持后台运行
  - _需求：FR-4 后台播放、熄屏播放_

- [ ] **3.2 实现 AudioPlayer 音频播放器**
  - 文件：`service/audio/AudioPlayer.java`
  - 封装 MediaPlayer
  - 支持播放、暂停、停止、切换
  - 实现播放完成回调
  - _需求：FR-3 音频播放_

- [ ] **3.3 实现 PlaylistManager 播放列表管理**
  - 文件：`service/audio/PlaylistManager.java`
  - 管理当前播放列表
  - 实现顺序播放逻辑
  - 实现随机播放逻辑
  - _需求：FR-3 顺序/随机播放_

- [ ] **3.4 实现 MixingPlaybackManager 混音管理**
  - 文件：`service/audio/MixingPlaybackManager.java`
  - 管理多个 AudioPlayer 实例
  - 支持多任务同时播放
  - 实现任务级别的播放控制
  - _需求：FR-3 多任务混音_

- [ ] **3.5 创建 TaskSchedulerService 调度服务**
  - 文件：`service/TaskSchedulerService.java`
  - 使用 AlarmManager 设置精确闹钟
  - 实现任务开始/结束时间调度
  - 处理跨天任务逻辑
  - _需求：FR-3 定时播放_

- [ ] **3.6 创建 AlarmReceiver 闹钟接收器**
  - 文件：`receiver/AlarmReceiver.java`
  - 接收 AlarmManager 广播
  - 区分任务开始/结束事件
  - 调用 PlaybackService 控制播放
  - _需求：FR-3 定时触发_

- [ ] **3.7 创建 BootReceiver 开机接收器**
  - 文件：`receiver/BootReceiver.java`
  - 接收 BOOT_COMPLETED 广播
  - 重新调度所有启用的任务
  - _需求：NFR-3 崩溃恢复_

- [ ] **3.8 实现 WakeLockManager 唤醒锁管理**
  - 文件：`service/WakeLockManager.java`
  - 管理 WakeLock 获取和释放
  - 确保熄屏状态下正常播放
  - _需求：FR-4 熄屏播放_

---

### 阶段 4：UI 层实现（预计 14 小时）

- [ ] **4.1 创建 MainActivity 主界面**
  - 文件：`ui/MainActivity.java`
  - 布局：`activity_main.xml`
  - 显示任务列表
  - 添加 FAB 按钮创建新任务
  - _需求：FR-2 任务列表查看_

- [ ] **4.2 创建 TaskAdapter 列表适配器**
  - 文件：`ui/adapter/TaskAdapter.java`
  - 布局：`item_task.xml`
  - 显示任务信息：名称、时间、状态
  - 启用/禁用开关
  - _需求：FR-2 任务管理_

- [ ] **4.3 创建 TaskViewModel**
  - 文件：`ui/viewmodel/TaskViewModel.java`
  - 连接 Repository 和 UI
  - 提供 LiveData 数据流
  - _需求：FR-2 任务管理_

- [ ] **4.4 创建 CreateTaskActivity 任务创建界面**
  - 文件：`ui/CreateTaskActivity.java`
  - 布局：`activity_create_task.xml`
  - 任务名称输入
  - 时间范围选择（TimePicker）
  - 播放模式选择（RadioButton）
  - 重复规则选择
  - _需求：FR-1 任务创建_

- [ ] **4.5 实现音频文件选择功能**
  - 使用 Intent.ACTION_OPEN_DOCUMENT 选择文件
  - 支持多选
  - 显示已选择的音频列表
  - 支持删除已选音频
  - _需求：FR-1 音频选择_

- [ ] **4.6 创建 EditTaskActivity 任务编辑界面**
  - 文件：`ui/EditTaskActivity.java`
  - 复用 CreateTaskActivity 布局
  - 加载现有任务数据
  - 支持修改和保存
  - _需求：FR-2 任务编辑_

- [ ] **4.7 实现任务删除功能**
  - 滑动删除或长按菜单
  - 删除确认对话框
  - 同时取消相关闹钟
  - _需求：FR-2 任务删除_

- [ ] **4.8 实现权限请求流程**
  - 文件：`ui/PermissionHelper.java`
  - 运行时权限请求
  - 精确闹钟权限引导
  - 电池优化白名单引导
  - _需求：NFR-4 权限管理_

---

### 阶段 5：系统集成与优化（预计 6 小时）

- [ ] **5.1 实现任务状态同步**
  - 任务启用时设置闹钟
  - 任务禁用时取消闹钟
  - 任务修改时更新闹钟
  - _需求：FR-2 启用/禁用任务_

- [ ] **5.2 实现音频文件有效性检查**
  - 播放前检查文件是否存在
  - 文件不存在时跳过并提示
  - 记录失效文件日志
  - _需求：FR-5 文件缺失处理_

- [ ] **5.3 实现通知管理**
  - 播放中显示前台通知
  - 通知显示当前播放任务
  - 通知操作：暂停/停止
  - _需求：FR-4 后台播放_

- [ ] **5.4 实现错误处理和日志**
  - 统一异常处理
  - 关键操作日志记录
  - 用户友好的错误提示
  - _需求：NFR 稳定性_

- [ ] **5.5 UI 优化和美化**
  - Material Design 风格
  - 空状态提示
  - 加载状态显示
  - _需求：用户体验_

---

### 阶段 5.5：任务执行日志功能（预计 6 小时）【新增】

- [x] **5.5.1 创建 TaskLog 实体类**
  - 文件：`data/entity/TaskLogEntity.java`
  - 字段：id, taskId, startTime, endTime, status, playedFiles, errorType, errorMessage, createdAt
  - Room 注解配置，外键关联 Task
  - 添加索引优化查询
  - _需求：FR-6 任务执行日志_

- [x] **5.5.2 创建日志状态常量类**
  - 文件：`util/LogStatus.java`
  - 定义状态常量：IN_PROGRESS(0), SUCCESS(1), FAILED(2)
  - 文件：`util/LogErrorType.java`
  - 定义错误类型：FILE_MISSING(1), PERMISSION_DENIED(2), PLAYER_ERROR(3), OTHER(4)
  - _需求：FR-6 验收标准3_

- [x] **5.5.3 创建 TaskLogDao 接口**
  - 文件：`data/dao/TaskLogDao.java`
  - 方法：insert, update, getLogsByTaskId, getInProgressLog, deleteLogsOlderThan
  - _需求：FR-6 日志管理_

- [x] **5.5.4 创建 TaskLogRepository 类**
  - 文件：`data/repository/TaskLogRepository.java`
  - 封装日志操作：createLog, updateLogSuccess, updateLogFailed, cleanOldLogs
  - 提供 LiveData 支持
  - _需求：FR-6 日志管理_

- [x] **5.5.5 更新 AppDatabase**
  - 添加 TaskLogEntity 到 entities
  - 添加 taskLogDao() 方法
  - 数据库版本升级和迁移
  - _需求：FR-6 数据持久化_

- [x] **5.5.6 集成日志记录到 PlaybackService**
  - 任务开始时创建日志（状态：进行中）
  - 任务结束时更新日志（状态：成功，记录播放文件）
  - 异常时更新日志（状态：失败，记录错误信息）
  - _需求：FR-6 验收标准1,2,3_

- [x] **5.5.7 创建 TaskLogActivity 日志列表界面**
  - 文件：`presentation/ui/log/TaskLogActivity.java`
  - 布局：`activity_task_log.xml`
  - 显示任务名称和日志列表
  - 按时间倒序排列
  - _需求：FR-6 验收标准4,5_

- [x] **5.5.8 创建 TaskLogAdapter 日志列表适配器**
  - 文件：`presentation/ui/log/TaskLogAdapter.java`
  - 布局：`item_task_log.xml`
  - 显示：执行时间、状态、播放文件数、失败原因
  - 状态图标和颜色区分
  - _需求：FR-6 验收标准7_

- [x] **5.5.9 更新 TaskEditActivity 添加查看日志入口**
  - 添加"查看执行日志"按钮
  - 点击跳转到 TaskLogActivity
  - 仅编辑模式显示（新建任务不显示）
  - _需求：FR-6 验收标准4_

- [x] **5.5.10 实现日志自动清理**
  - 在应用启动时清理 30 天前的日志
  - 使用 WorkManager 定期清理
  - _需求：FR-6 验收标准6_

---

### 阶段 6：华为设备后台保活适配（预计 6 小时）

- [ ] **6.1 创建 HuaweiDeviceHelper 华为设备检测**
  - 文件：`util/HuaweiDeviceHelper.java`
  - 检测是否为华为/荣耀设备
  - 检测 EMUI 版本
  - _需求：华为适配_

- [ ] **6.2 实现华为自启动权限引导**
  - 文件：`ui/HuaweiPermissionGuideActivity.java`
  - 检测自启动权限状态
  - 引导用户跳转到华为手机管家
  - 提供图文引导说明
  - _需求：华为后台保活_

- [ ] **6.3 实现华为电池优化白名单引导**
  - 检测应用是否在电池优化白名单
  - 引导用户关闭电池优化
  - 引导用户开启"允许后台活动"
  - _需求：华为后台保活_

- [ ] **6.4 实现华为后台应用管控适配**
  - 处理华为系统的后台应用清理
  - 使用 WorkManager 作为 AlarmManager 的备份方案
  - 实现任务恢复机制
  - _需求：华为后台保活_

- [ ] **6.5 创建权限引导对话框**
  - 文件：`ui/dialog/PermissionGuideDialog.java`
  - 首次启动时显示权限引导
  - 提供"不再提示"选项
  - 检测权限状态并更新 UI
  - _需求：用户体验_

- [ ] **6.6 实现华为推送保活（可选）**
  - 研究华为推送服务唤醒机制
  - 评估是否需要集成华为推送
  - 如需要则实现基础集成
  - _需求：华为后台保活增强_

---

### 阶段 7：模拟器测试（预计 8 小时）

#### 7.1 手动功能测试

- [ ] **7.1.1 编写功能测试用例文档**
  - 文件：`specs/noise_retaliation/test_cases.md`
  - 任务创建测试用例
  - 任务编辑/删除测试用例
  - 定时播放测试用例
  - 后台播放测试用例
  - 多任务混音测试用例
  - _需求：所有 FR_

- [ ] **7.1.2 Android 模拟器环境搭建**
  - 创建 Android 8.0 (API 26) 模拟器
  - 创建 Android 10 (API 29) 模拟器
  - 创建 Android 12 (API 31) 模拟器
  - 创建 Android 13 (API 33) 模拟器
  - 准备测试音频文件
  - _需求：多版本兼容性_

- [ ] **7.1.3 执行手动功能测试**
  - 按测试用例逐项验证
  - 记录测试结果
  - 记录发现的 Bug
  - _需求：功能验收_

#### 7.2 Espresso 自动化 UI 测试

- [ ] **7.2.1 配置 Espresso 测试环境**
  - 添加 Espresso 依赖到 build.gradle
  - 配置测试运行器
  - 创建测试基类
  - _需求：自动化测试基础_

- [ ] **7.2.2 编写任务创建自动化测试**
  - 文件：`androidTest/ui/CreateTaskActivityTest.java`
  - 测试输入任务名称
  - 测试时间选择
  - 测试播放模式选择
  - 测试保存任务
  - _需求：FR-1 任务创建_

- [ ] **7.2.3 编写任务列表自动化测试**
  - 文件：`androidTest/ui/MainActivityTest.java`
  - 测试任务列表显示
  - 测试任务启用/禁用
  - 测试任务删除
  - 测试空状态显示
  - _需求：FR-2 任务管理_

- [ ] **7.2.4 编写任务编辑自动化测试**
  - 文件：`androidTest/ui/EditTaskActivityTest.java`
  - 测试加载现有任务
  - 测试修改任务信息
  - 测试保存修改
  - _需求：FR-2 任务编辑_

#### 7.3 多版本兼容性测试

- [ ] **7.3.1 Android 8.0 兼容性测试**
  - 在 API 26 模拟器上运行所有测试
  - 验证前台服务通知
  - 验证精确闹钟权限
  - 记录兼容性问题
  - _需求：NFR-1 兼容性_

- [ ] **7.3.2 Android 10 兼容性测试**
  - 在 API 29 模拟器上运行所有测试
  - 验证分区存储适配
  - 验证后台启动限制
  - 记录兼容性问题
  - _需求：NFR-1 兼容性_

- [ ] **7.3.3 Android 12+ 兼容性测试**
  - 在 API 31/33 模拟器上运行所有测试
  - 验证精确闹钟权限（SCHEDULE_EXACT_ALARM）
  - 验证前台服务类型声明
  - 验证通知权限
  - 记录兼容性问题
  - _需求：NFR-1 兼容性_

---

### 阶段 8：华为真机验证测试（预计 4 小时）

- [ ] **8.1 华为真机测试环境准备**
  - 准备华为测试设备（建议 EMUI 10+）
  - 安装 Debug APK
  - 准备测试音频文件
  - _需求：华为适配验证_

- [ ] **8.2 华为后台保活功能验证**
  - 测试前台服务在华为设备上的稳定性
  - 测试锁屏后播放是否正常
  - 测试清理后台后任务是否恢复
  - 测试重启设备后任务是否自动恢复
  - _需求：FR-4 后台播放_

- [ ] **8.3 华为定时功能验证**
  - 测试 AlarmManager 在华为设备上的准确性
  - 测试省电模式下定时是否正常
  - 测试跨天任务定时
  - 记录定时误差数据
  - _需求：FR-3 定时播放、NFR-2 定时精度_

- [ ] **8.4 华为权限引导验证**
  - 验证自启动权限引导流程
  - 验证电池优化白名单引导流程
  - 验证后台应用管控引导流程
  - 确保引导文案和截图与实际界面一致
  - _需求：华为适配_

- [ ] **8.5 华为长时间稳定性测试**
  - 设置多个任务运行 24 小时
  - 监控内存使用情况
  - 监控电池消耗情况
  - 记录任何异常情况
  - _需求：NFR 稳定性_

- [ ] **8.6 Bug 修复与回归测试**
  - 修复华为真机测试发现的问题
  - 在模拟器上进行回归测试
  - 在华为真机上验证修复
  - _需求：质量保证_

---

## 依赖关系

```
阶段1 → 阶段2 → 阶段3 → 阶段4 → 阶段5 → 阶段6 → 阶段7 → 阶段8
                  ↓
              可并行开发
              （3.1-3.4 与 4.1-4.4）
              （阶段6 与 阶段5 可并行）
```

## 技术风险

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| AlarmManager 在不同厂商 ROM 上行为不一致 | 定时不准确 | 使用 setExactAndAllowWhileIdle + 电池优化白名单 |
| 后台服务被系统杀死 | 播放中断 | 前台服务 + 通知 + 开机自启 |
| 音频焦点冲突 | 与其他 App 冲突 | 合理处理音频焦点，提供用户选项 |
| 大量音频文件导致内存问题 | App 崩溃 | 延迟加载 + 及时释放资源 |
| **华为 EMUI 后台限制严格** | **后台播放中断** | **自启动权限 + 电池优化白名单 + 后台应用管控引导** |
| **华为设备 AlarmManager 不准确** | **定时偏差大** | **WorkManager 备份 + 用户引导关闭省电模式** |

---

## 完成标准

每个任务完成后应满足：
1. 代码编译通过，无警告
2. 功能符合对应需求的验收标准
3. 基本的错误处理已实现
4. 代码结构清晰，有必要的注释
