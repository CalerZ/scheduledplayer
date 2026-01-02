# 定时音乐播放器 (Scheduled Music Player)

Android 定时音乐播放器应用，在指定时间自动播放音乐。

## 核心功能

### 1. 任务管理
- 创建/编辑/删除播放任务
- 设置任务名称、开始时间、结束时间
- 支持全天播放模式
- 设置重复日期（周一至周日）
- 任务启用/禁用开关

### 2. 音频播放
- 支持选择多个音频文件
- 支持从歌单导入音频
- 三种播放模式：单曲播放、随机播放、循环列表
- 音量控制
- 播放/暂停控制
- 实时显示播放进度和歌曲名

### 3. 输出设备
- 支持选择输出设备（扬声器/蓝牙）
- 蓝牙断开时自动停止仅蓝牙任务

### 4. 后台服务
- 前台服务保持播放
- 精确闹钟定时触发
- WakeLock 保持设备唤醒
- 音频焦点管理

### 5. 华为设备适配
- 自启动权限引导
- 电池优化白名单引导
- 后台运行权限引导

### 6. 任务日志
- 记录任务执行历史
- 显示执行状态（成功/失败/进行中）
- 记录播放的文件列表
- 记录错误原因

## 技术架构

```
app/src/main/java/com/caleb/scheduledplayer/
├── data/
│   ├── database/      # Room 数据库
│   ├── dao/           # 数据访问对象
│   ├── entity/        # 实体类 (TaskEntity, TaskLogEntity)
│   ├── repository/    # 数据仓库
│   └── converter/     # 类型转换器
├── presentation/
│   ├── ui/
│   │   ├── main/      # 主界面 (MainActivity, TaskAdapter)
│   │   ├── task/      # 任务编辑 (TaskEditActivity)
│   │   └── music/     # 音乐管理
│   └── viewmodel/     # ViewModel
├── service/
│   ├── player/        # 播放服务 (AudioPlaybackService)
│   └── scheduler/     # 任务调度 (TaskSchedulerService)
└── util/              # 工具类
```

## 技术栈

- **语言**: Java
- **最低 SDK**: Android 8.0 (API 26)
- **数据库**: Room
- **架构**: MVVM
- **依赖注入**: 手动依赖注入
- **UI**: Material Design 3

## 构建

```bash
./gradlew assembleDebug
```

APK 输出路径: `app/build/outputs/apk/debug/app-debug.apk`

## 待开发功能

- **噪音反击 (Noise Retaliation)**: 检测到噪音时自动播放音乐进行反击，详见 `specs/noise_retaliation/` 目录
