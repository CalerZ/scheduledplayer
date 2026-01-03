# 定时音乐播放器 - 系统架构文档

## 1. 项目概述

这是一个 Android 定时音乐播放器应用，支持用户创建定时播放任务，在指定时间自动播放音乐。

### 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Java 17 |
| 架构模式 | MVVM |
| 数据库 | Room (SQLite) |
| UI | Material Design 3, ViewBinding |
| 定时调度 | AlarmManager (setAlarmClock) |
| 后台服务 | Foreground Service, WorkManager |
| 音频播放 | MediaPlayer |

---

## 2. 系统架构

### 2.1 整体架构图

```
┌─────────────────────────────────────────────────────────────┐
│                      表现层 (Presentation)                   │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │ MainActivity│  │TaskEditAct. │  │   TaskLogActivity   │  │
│  └──────┬──────┘  └──────┬──────┘  └──────────┬──────────┘  │
│         │                │                     │             │
│  ┌──────▼──────┐  ┌──────▼──────┐              │             │
│  │MainViewModel│  │TaskEditVM   │              │             │
│  └──────┬──────┘  └──────┬──────┘              │             │
└─────────┼────────────────┼─────────────────────┼─────────────┘
          │                │                     │
┌─────────▼────────────────▼─────────────────────▼─────────────┐
│                      数据层 (Data)                            │
│  ┌─────────────────┐  ┌─────────────────────────────────┐    │
│  │ TaskRepository  │  │      TaskLogRepository          │    │
│  └────────┬────────┘  └────────────────┬────────────────┘    │
│           │                            │                     │
│  ┌────────▼────────┐  ┌────────────────▼────────────────┐    │
│  │    TaskDao      │  │         TaskLogDao              │    │
│  └────────┬────────┘  └────────────────┬────────────────┘    │
│           │                            │                     │
│  ┌────────▼────────────────────────────▼────────────────┐    │
│  │              AppDatabase (Room, 版本7)                │    │
│  └──────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│                      服务层 (Service)                         │
│  ┌────────────────────────┐  ┌────────────────────────────┐  │
│  │  AudioPlaybackService  │  │   TaskSchedulerService     │  │
│  │   (前台服务/音频播放)    │  │    (任务调度/闹钟管理)      │  │
│  └────────────────────────┘  └────────────────────────────┘  │
│                                                              │
│  ┌────────────────────────┐  ┌────────────────────────────┐  │
│  │     AlarmReceiver      │  │      BootReceiver          │  │
│  │    (闹钟触发接收器)      │  │     (开机广播接收器)        │  │
│  └────────────────────────┘  └────────────────────────────┘  │
│                                                              │
│  ┌────────────────────────┐                                  │
│  │    TaskCheckWorker     │                                  │
│  │  (WorkManager 备份检查) │                                  │
│  └────────────────────────┘                                  │
└──────────────────────────────────────────────────────────────┘
```

### 2.2 目录结构

```
com.caleb.scheduledplayer/
├── ScheduledPlayerApp.java              # Application 入口
├── data/                                # 数据层
│   ├── converter/Converters.java        # Room 类型转换器
│   ├── dao/
│   │   ├── TaskDao.java                 # 任务数据访问
│   │   └── TaskLogDao.java              # 日志数据访问
│   ├── database/AppDatabase.java        # Room 数据库 (版本7)
│   ├── entity/
│   │   ├── TaskEntity.java              # 任务实体
│   │   └── TaskLogEntity.java           # 日志实体
│   └── repository/
│       ├── TaskRepository.java          # 任务仓库
│       └── TaskLogRepository.java       # 日志仓库
├── presentation/                        # 表现层
│   ├── ui/
│   │   ├── main/
│   │   │   ├── MainActivity.java        # 主界面
│   │   │   └── TaskAdapter.java         # 任务列表适配器
│   │   ├── task/TaskEditActivity.java   # 任务编辑界面
│   │   ├── log/TaskLogActivity.java     # 日志查看界面
│   │   ├── music/                       # 音乐选择相关
│   │   └── widget/                      # 自定义组件
│   └── viewmodel/
│       ├── MainViewModel.java           # 主界面 ViewModel
│       └── TaskEditViewModel.java       # 编辑界面 ViewModel
├── service/                             # 服务层
│   ├── player/
│   │   └── AudioPlaybackService.java    # 音频播放前台服务
│   ├── scheduler/
│   │   ├── TaskSchedulerService.java    # 任务调度服务
│   │   ├── AlarmReceiver.java           # 闹钟广播接收器
│   │   └── BootReceiver.java            # 开机广播接收器
│   └── worker/
│       └── TaskCheckWorker.java         # WorkManager 备份检查
└── util/                                # 工具类
    ├── BluetoothHelper.java             # 蓝牙辅助类
    ├── AudioFileValidator.java          # 音频文件验证
    ├── LogErrorType.java                # 错误类型常量
    └── WakeLockManager.java             # 唤醒锁管理
```

---

## 3. 核心模块详解

### 3.1 任务调度系统

#### 调度流程

```
用户创建/启用任务
        │
        ▼
TaskSchedulerService.scheduleTask()
        │
        ├─── 全天播放模式 ───► 立即调用 AudioPlaybackService.startTaskPlayback()
        │
        └─── 定时播放模式 ───► 计算开始/结束时间
                                    │
                                    ├─── 当前在时间范围内 → 立即播放 + 设置结束闹钟
                                    │
                                    └─── 当前不在范围内 → 设置开始闹钟 + 结束闹钟
                                                │
                                                ▼
                                    AlarmManager.setAlarmClock() (最高优先级)
                                                │
                                                ▼
                                    闹钟触发 → AlarmReceiver.onReceive()
                                                │
                                                ▼
                                    AudioPlaybackService.startTaskPlayback()
```

#### 闹钟类型选择

| 场景 | API | 原因 |
|------|-----|------|
| 开始播放 | `setAlarmClock()` | 最高优先级，Doze 模式也能触发 |
| 结束播放 | `setExactAndAllowWhileIdle()` | 精确触发，允许 Doze 唤醒 |

### 3.2 音频播放服务

#### AudioPlaybackService 核心功能

- **前台服务**：保持应用存活，显示通知
- **多任务混音**：支持多个任务同时播放
- **WakeLock**：防止设备休眠
- **蓝牙监听**：蓝牙断开时停止相关任务

#### TaskPlayer 内部类

每个播放任务对应一个 `TaskPlayer` 实例：

```java
private class TaskPlayer {
    - TaskEntity task        // 任务信息
    - List<String> playlist  // 播放列表
    - MediaPlayer mediaPlayer
    - int currentIndex       // 当前播放索引
    - boolean isPlaying
    
    + start()                // 开始播放
    + stop()                 // 停止播放
    + playCurrentTrack()     // 播放当前曲目
    + onCompletion()         // 播放完成回调
    + onError()              // 错误处理回调
}
```

### 3.3 数据模型

#### TaskEntity 核心字段

| 字段 | 类型 | 说明 |
|------|------|------|
| id | long | 主键 |
| name | String | 任务名称 |
| enabled | boolean | 是否启用 |
| startTime | String | 开始时间 (HH:mm) |
| endTime | String | 结束时间 (HH:mm) |
| audioPaths | String | 音频路径 (JSON) |
| playMode | int | 播放模式 (顺序/随机/单曲循环) |
| volume | int | 音量 (0-100) |
| repeatDays | int | 重复日期 (位运算) |
| outputDevice | int | 输出设备 (默认/蓝牙) |
| allDayPlay | boolean | 全天播放模式 |

---

## 4. 可靠性保障机制

### 4.1 多重调度保障

| 层级 | 机制 | 触发时机 | 可靠性 |
|------|------|----------|--------|
| **主要** | AlarmManager.setAlarmClock() | 精确时间触发 | ⭐⭐⭐⭐⭐ |
| **备份1** | BootReceiver | 设备开机后 | ⭐⭐⭐⭐ |
| **备份2** | TaskCheckWorker | 每15分钟检查 | ⭐⭐⭐⭐ |
| **备份3** | MainActivity.onCreate() | 打开应用时 | ⭐⭐⭐ |

### 4.2 服务保活策略

```java
// 1. 前台服务
startForeground(NOTIFICATION_ID, notification);

// 2. START_STICKY - 服务被杀死后自动重启
return START_STICKY;

// 3. WakeLock - 防止 CPU 休眠
wakeLock.acquire();
```

### 4.3 开机自启动

支持的开机广播：

```xml
<action android:name="android.intent.action.BOOT_COMPLETED" />
<action android:name="android.intent.action.QUICKBOOT_POWERON" />
<action android:name="com.htc.intent.action.QUICKBOOT_POWERON" />
<action android:name="com.huawei.intent.action.QUICKBOOT_POWERON" />
```

---

## 5. 错误处理机制

### 5.1 异常类型及处理策略

| 异常类型 | 处理方式 | 代码位置 |
|---------|----------|----------|
| **音频焦点被抢占** | 忽略，继续播放 | `AudioPlaybackService.requestAudioFocus()` |
| **蓝牙断开** | 停止蓝牙任务 | `AudioPlaybackService.stopBluetoothOnlyTasks()` |
| **MediaPlayer 错误** | 记录日志，跳到下一首 | `TaskPlayer.onError()` |
| **文件不存在** | 记录日志，跳到下一首 | `TaskPlayer.playCurrentTrack()` |
| **权限被拒绝** | 记录日志，跳到下一首 | `TaskPlayer.playCurrentTrack()` |
| **系统杀死服务** | START_STICKY 自动重启 | `AudioPlaybackService.onStartCommand()` |
| **设备休眠** | WakeLock 保持唤醒 | `AudioPlaybackService.acquireWakeLock()` |
| **闹钟被取消** | TaskCheckWorker 补救 | `TaskCheckWorker.doWork()` |

### 5.2 错误日志记录

错误类型定义 (`LogErrorType.java`):

```java
public class LogErrorType {
    public static final int NONE = 0;
    public static final int FILE_MISSING = 1;        // 文件不存在
    public static final int PERMISSION_DENIED = 2;   // 权限被拒绝
    public static final int PLAYER_ERROR = 3;        // 播放器错误
    public static final int BLUETOOTH_NOT_CONNECTED = 4;  // 蓝牙未连接
    public static final int BLUETOOTH_DISCONNECTED = 5;   // 蓝牙断开
}
```

### 5.3 播放错误处理流程

```
MediaPlayer 发生错误
        │
        ▼
TaskPlayer.onError(what, extra)
        │
        ├─── 记录错误日志 (recordPlaybackError)
        │
        ├─── currentIndex++  (跳到下一首)
        │
        └─── playCurrentTrack()  (继续播放)
                │
                └─── 如果所有文件都失败 → 循环回第一首重试
```

---

## 6. 数据库迁移历史

| 版本 | 变更内容 |
|------|----------|
| 1 → 2 | 初始迁移 |
| 2 → 3 | 添加 output_device 字段 |
| 3 → 4 | 添加 all_day_play 字段 |
| 4 → 5 | 创建 task_logs 表 |
| 5 → 6 | 添加随机暂停字段 (已废弃) |
| 6 → 7 | 删除随机暂停字段，重建表 |

---

## 7. 华为设备适配

针对华为设备的特殊处理：

1. **权限引导**：提示用户开启自启动权限
2. **WorkManager 备份**：华为设备闹钟可能不可靠，使用 WorkManager 作为备份
3. **快速启动广播**：监听 `com.huawei.intent.action.QUICKBOOT_POWERON`

---

## 8. 设计模式

| 模式 | 应用场景 |
|------|----------|
| **单例模式** | `AppDatabase.getInstance()` |
| **仓库模式** | `TaskRepository`, `TaskLogRepository` |
| **观察者模式** | `LiveData` + `Observer` |
| **策略模式** | 播放模式 (顺序/随机/循环) |
| **建造者模式** | `NotificationCompat.Builder`, `AudioFocusRequest.Builder` |

---

## 9. 关键配置

### AndroidManifest.xml 权限

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<uses-permission android:name="android.permission.USE_EXACT_ALARM" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```

---

*文档版本: 1.0*  
*最后更新: 2026-01-03*
