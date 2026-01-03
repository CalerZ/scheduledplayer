# 定时音乐播放器 (Scheduled Music Player)

一款 Android 定时音乐播放器应用，支持在指定时间自动播放音乐，适用于定时提醒、背景音乐播放等场景。

## 功能特性

### 🎵 任务管理
- 创建/编辑/删除播放任务
- 设置开始时间和结束时间
- 支持全天播放模式
- 设置重复日期（周一至周日）
- 任务启用/禁用开关

### 🎧 音频播放
- 支持选择多个音频文件
- 支持从系统歌单导入音频
- 三种播放模式：顺序播放、随机播放、单曲循环
- 音量控制（0-100%）
- 实时显示播放进度和当前歌曲

### 📱 输出设备
- 支持扬声器输出
- 支持蓝牙设备输出
- 蓝牙断开时自动停止仅蓝牙任务

### 🔒 可靠性保障
- 前台服务保持播放不被杀死
- 精确闹钟（setAlarmClock）定时触发
- WakeLock 防止设备休眠
- 开机自动恢复任务
- WorkManager 每15分钟备份检查

### 📋 任务日志
- 记录任务执行历史
- 显示执行状态（成功/失败/进行中）
- 记录播放的文件和错误信息

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Java 17 |
| 架构模式 | MVVM |
| 数据库 | Room (SQLite) |
| UI | Material Design 3, ViewBinding |
| 定时调度 | AlarmManager (setAlarmClock) |
| 后台服务 | Foreground Service, WorkManager |
| 音频播放 | MediaPlayer |

## 系统要求

- **最低 SDK**: Android 8.0 (API 26)
- **目标 SDK**: Android 14 (API 34)

## 项目结构

```
app/src/main/java/com/caleb/scheduledplayer/
├── data/                           # 数据层
│   ├── database/                   # Room 数据库
│   ├── dao/                        # 数据访问对象
│   ├── entity/                     # 实体类
│   ├── repository/                 # 数据仓库
│   └── converter/                  # 类型转换器
├── presentation/                   # 表现层
│   ├── ui/
│   │   ├── main/                   # 主界面
│   │   ├── task/                   # 任务编辑
│   │   ├── log/                    # 日志查看
│   │   └── music/                  # 音乐选择
│   └── viewmodel/                  # ViewModel
├── service/                        # 服务层
│   ├── player/                     # 音频播放服务
│   ├── scheduler/                  # 任务调度服务
│   └── worker/                     # WorkManager 任务
└── util/                           # 工具类
```

## 构建与安装

### 构建 Debug 版本
```bash
./gradlew assembleDebug
```

### 安装到设备
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 权限说明

| 权限 | 用途 |
|------|------|
| `FOREGROUND_SERVICE` | 前台服务保持播放 |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | 媒体播放前台服务 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启动恢复任务 |
| `WAKE_LOCK` | 防止设备休眠 |
| `SCHEDULE_EXACT_ALARM` | 精确定时闹钟 |
| `READ_MEDIA_AUDIO` | 读取音频文件 |
| `BLUETOOTH_CONNECT` | 蓝牙设备连接 |

## 华为设备适配

针对华为设备的特殊处理：
- 自启动权限引导
- 电池优化白名单引导
- 后台运行权限引导

## 错误处理

| 异常类型 | 处理方式 |
|---------|----------|
| 音频焦点被抢占 | 忽略，继续播放 |
| 蓝牙断开 | 停止蓝牙任务 |
| MediaPlayer 错误 | 跳到下一首 |
| 文件不存在 | 跳到下一首 |
| 系统杀死服务 | 自动重启 |
| 设备休眠 | WakeLock 保持唤醒 |

## 文档

- [系统架构文档](docs/ARCHITECTURE.md)

## License

MIT License
