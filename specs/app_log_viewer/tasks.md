# 实施计划 - 应用日志查看与导出功能

## 实施概览
预计总工时：6-8小时
关键里程碑：
1. 日志工具类完成 → 应用日志可写入文件
2. 日志界面完成 → 用户可查看日志
3. 分享功能完成 → 用户可导出日志

## 任务列表

### 1. 基础设施层

- [ ] 1.1 创建AppLogger日志工具类
  - 实现单例模式
  - 实现d/i/w/e日志方法
  - 同时输出到Logcat和文件
  - 异步写入（ExecutorService）
  - 文件命名：app_YYYYMMDD.log
  - _需求：FR-1 验收标准1,2_

- [ ] 1.2 实现日志文件轮转和清理
  - 检查文件大小，超过500MB创建新文件
  - 应用启动时清理7天前的旧文件
  - 实现getLogFiles()、getTotalLogSize()、clearAllLogs()方法
  - _需求：FR-1 验收标准3_

- [ ] 1.3 在Application中初始化AppLogger
  - ScheduledPlayerApp.onCreate()中调用AppLogger.init()
  - _需求：FR-1 验收标准1_

### 2. 资源文件

- [ ] 2.1 创建日志图标和布局资源
  - 创建ic_log.xml图标（或使用Material图标）
  - 创建activity_log_viewer.xml布局
  - 创建item_log.xml日志条目布局
  - 创建menu_log_viewer.xml菜单（搜索、分享、清空）
  - _需求：FR-2 验收标准1_

- [ ] 2.2 配置FileProvider
  - AndroidManifest.xml添加FileProvider
  - 创建res/xml/file_paths.xml
  - _需求：FR-3 验收标准1_

- [ ] 2.3 修改抽屉菜单
  - menu_drawer.xml添加"日志"菜单项
  - strings.xml添加相关字符串
  - _需求：FR-2 验收标准1_

### 3. UI层实现

- [ ] 3.1 创建LogEntry模型类
  - 字段：timestamp、level、tag、message
  - 实现parse()静态方法解析日志行
  - _需求：FR-2 验收标准2_

- [ ] 3.2 创建LogAdapter适配器
  - 继承RecyclerView.Adapter
  - 根据日志级别设置颜色（ERROR红色、WARN橙色）
  - _需求：FR-2 验收标准4_

- [ ] 3.3 创建LogViewerActivity
  - 实现onCreate()初始化布局
  - 异步加载日志文件
  - 显示存储占用信息
  - _需求：FR-2 验收标准1,2_

- [ ] 3.4 实现日志搜索过滤
  - Toolbar添加SearchView
  - 实现filterLogs()按关键字过滤
  - _需求：FR-2 验收标准5_

- [ ] 3.5 实现日志分享功能
  - 通过FileProvider生成URI
  - 调用Intent.ACTION_SEND分享
  - _需求：FR-3 验收标准1,2,3_

- [ ] 3.6 实现日志清空功能
  - 弹出确认对话框
  - 调用AppLogger.clearAllLogs()
  - 刷新界面
  - _需求：FR-4 验收标准1,2_

### 4. 集成

- [ ] 4.1 MainActivity集成日志入口
  - onNavigationItemSelected()处理nav_logs点击
  - 启动LogViewerActivity
  - _需求：FR-2 验收标准1_

- [ ] 4.2 AndroidManifest注册Activity
  - 注册LogViewerActivity
  - _需求：FR-2 验收标准1_

### 5. 代码迁移（可选，后续优化）

- [ ] 5.1 替换现有Log调用为AppLogger
  - 逐步将各模块的Log.d/i/w/e替换为AppLogger
  - 优先替换核心服务：AudioPlaybackService、TaskSchedulerService
  - _需求：FR-1 验收标准2_

## 依赖关系
```
1.1 → 1.2 → 1.3 (日志工具类)
2.1 + 2.2 + 2.3 (资源文件，可并行)
3.1 → 3.2 → 3.3 → 3.4/3.5/3.6 (UI层)
4.1 + 4.2 (集成)
```

## 执行顺序建议
1. 先完成 1.1、1.2、1.3（日志基础设施）
2. 并行完成 2.1、2.2、2.3（资源文件）
3. 顺序完成 3.1 → 3.2 → 3.3（核心UI）
4. 并行完成 3.4、3.5、3.6（功能完善）
5. 完成 4.1、4.2（集成）
6. 可选：5.1（代码迁移）
