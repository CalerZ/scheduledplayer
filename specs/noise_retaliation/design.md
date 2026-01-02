# 设计文档 - 噪音反击 (Noise Retaliation)

## 概述

本文档描述基于 Android 原生 Java 开发的定时音频播放 App 的技术设计方案。采用 SQLite 数据库存储任务数据，使用前台服务实现后台和熄屏播放功能。

## 架构设计

### 系统架构图

```
┌─────────────────────────────────────────────────────────────┐
│                        UI Layer                              │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │ MainActivity│  │TaskEditActivity│ │ AudioPickerActivity│  │
│  └──────┬──────┘  └──────┬──────┘  └──────────┬──────────┘  │
└─────────┼────────────────┼───────────────────┼──────────────┘
          │                │                   │
          ▼                ▼                   ▼
┌─────────────────────────────────────────────────────────────┐
│                      Service Layer                           │
│  ┌─────────────────────┐  ┌─────────────────────────────┐   │
│  │ PlaybackService     │  │ TaskSchedulerService        │   │
│  │ (前台服务/音频播放)  │  │ (任务调度/AlarmManager)     │   │
│  └──────────┬──────────┘  └──────────────┬──────────────┘   │
└─────────────┼────────────────────────────┼──────────────────┘
              │                            │
              ▼                            ▼
┌─────────────────────────────────────────────────────────────┐
│                      Data Layer                              │
│  ┌─────────────────────┐  ┌─────────────────────────────┐   │
│  │ TaskRepository      │  │ AudioFileManager            │   │
│  │ (任务数据管理)       │  │ (音频文件管理)              │   │
│  └──────────┬──────────┘  └─────────────────────────────┘   │
│             │                                                │
│             ▼                                                │
│  ┌─────────────────────┐                                    │
│  │ SQLite Database     │                                    │
│  │ (Room ORM)          │                                    │
│  └─────────────────────┘                                    │
└─────────────────────────────────────────────────────────────┘
              │
              ▼
┌─────────────────────────────────────────────────────────────┐
│                   System Components                          │
│  ┌──────────────┐  ┌──────────────┐  ┌─────────────────┐   │
│  │ AlarmManager │  │ MediaPlayer  │  │ BootReceiver    │   │
│  │ (定时触发)    │  │ (音频播放)   │  │ (开机恢复)      │   │
│  └──────────────┘  └──────────────┘  └─────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### 技术栈

| 层级 | 技术选型 | 说明 |
|------|----------|------|
| 开发语言 | Java | Android 原生开发 |
| 最低版本 | Android 8.0 (API 26) | 支持通知渠道和前台服务 |
| 数据库 | Room + SQLite | ORM 框架简化数据库操作 |
| 定时调度 | AlarmManager | 精确定时触发 |
| 音频播放 | MediaPlayer | 支持多种音频格式 |
| 后台服务 | Foreground Service | 保持后台运行 |
| UI 框架 | Material Design | 原生 Android UI |

## 详细设计

### 数据模型

#### Task（任务实体）

```java
@Entity(tableName = "tasks")
public class Task {
    @PrimaryKey(autoGenerate = true)
    private long id;
    
    @ColumnInfo(name = "name")
    private String name;                    // 任务名称
    
    @ColumnInfo(name = "start_hour")
    private int startHour;                  // 开始时间-小时 (0-23)
    
    @ColumnInfo(name = "start_minute")
    private int startMinute;                // 开始时间-分钟 (0-59)
    
    @ColumnInfo(name = "end_hour")
    private int endHour;                    // 结束时间-小时 (0-23)
    
    @ColumnInfo(name = "end_minute")
    private int endMinute;                  // 结束时间-分钟 (0-59)
    
    @ColumnInfo(name = "play_mode")
    private int playMode;                   // 播放模式: 0=顺序, 1=随机
    
    @ColumnInfo(name = "repeat_type")
    private int repeatType;                 // 重复类型: 0=一次性, 1=每天, 2=指定星期
    
    @ColumnInfo(name = "repeat_days")
    private String repeatDays;              // 重复星期: "1,2,3,4,5" (周一到周五)
    
    @ColumnInfo(name = "is_enabled")
    private boolean isEnabled;              // 是否启用
    
    @ColumnInfo(name = "created_at")
    private long createdAt;                 // 创建时间
    
    @ColumnInfo(name = "updated_at")
    private long updatedAt;                 // 更新时间
}
```

#### TaskAudioFile（任务音频文件关联）

```java
@Entity(tableName = "task_audio_files",
        foreignKeys = @ForeignKey(
            entity = Task.class,
            parentColumns = "id",
            childColumns = "task_id",
            onDelete = ForeignKey.CASCADE
        ))
public class TaskAudioFile {
    @PrimaryKey(autoGenerate = true)
    private long id;
    
    @ColumnInfo(name = "task_id")
    private long taskId;                    // 关联任务ID
    
    @ColumnInfo(name = "file_path")
    private String filePath;                // 音频文件路径
    
    @ColumnInfo(name = "file_name")
    private String fileName;                // 文件名（显示用）
    
    @ColumnInfo(name = "duration")
    private long duration;                  // 音频时长（毫秒）
    
    @ColumnInfo(name = "sort_order")
    private int sortOrder;                  // 排序顺序
}
```

#### TaskLog（任务执行日志）

```java
@Entity(tableName = "task_logs",
        foreignKeys = @ForeignKey(
            entity = Task.class,
            parentColumns = "id",
            childColumns = "task_id",
            onDelete = ForeignKey.CASCADE
        ),
        indices = {@Index(value = "task_id"), @Index(value = "start_time")})
public class TaskLog {
    @PrimaryKey(autoGenerate = true)
    private long id;
    
    @ColumnInfo(name = "task_id")
    private long taskId;                    // 关联任务ID
    
    @ColumnInfo(name = "start_time")
    private long startTime;                 // 启动时间（时间戳）
    
    @ColumnInfo(name = "end_time")
    private Long endTime;                   // 结束时间（时间戳，可为空表示进行中）
    
    @ColumnInfo(name = "status")
    private int status;                     // 状态: 0=进行中, 1=成功, 2=失败
    
    @ColumnInfo(name = "played_files")
    private String playedFiles;             // 播放的音频文件列表（JSON数组）
    
    @ColumnInfo(name = "error_type")
    private Integer errorType;              // 错误类型: 1=文件缺失, 2=权限问题, 3=播放器错误, 4=其他
    
    @ColumnInfo(name = "error_message")
    private String errorMessage;            // 错误详细信息
    
    @ColumnInfo(name = "created_at")
    private long createdAt;                 // 记录创建时间
}
```

**日志状态枚举：**
```java
public class LogStatus {
    public static final int IN_PROGRESS = 0;  // 进行中
    public static final int SUCCESS = 1;       // 成功
    public static final int FAILED = 2;        // 失败
}

public class LogErrorType {
    public static final int FILE_MISSING = 1;      // 文件缺失
    public static final int PERMISSION_DENIED = 2; // 权限问题
    public static final int PLAYER_ERROR = 3;      // 播放器错误
    public static final int OTHER = 4;             // 其他异常
}
```

### 数据库 DAO

```java
@Dao
public interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY created_at DESC")
    List<Task> getAllTasks();
    
    @Query("SELECT * FROM tasks WHERE is_enabled = 1")
    List<Task> getEnabledTasks();
    
    @Query("SELECT * FROM tasks WHERE id = :taskId")
    Task getTaskById(long taskId);
    
    @Insert
    long insertTask(Task task);
    
    @Update
    void updateTask(Task task);
    
    @Delete
    void deleteTask(Task task);
    
    @Query("UPDATE tasks SET is_enabled = :enabled WHERE id = :taskId")
    void setTaskEnabled(long taskId, boolean enabled);
}

@Dao
public interface TaskAudioFileDao {
    @Query("SELECT * FROM task_audio_files WHERE task_id = :taskId ORDER BY sort_order")
    List<TaskAudioFile> getAudioFilesForTask(long taskId);
    
    @Insert
    void insertAudioFiles(List<TaskAudioFile> audioFiles);
    
    @Query("DELETE FROM task_audio_files WHERE task_id = :taskId")
    void deleteAudioFilesForTask(long taskId);
}

@Dao
public interface TaskLogDao {
    // 插入新日志，返回日志ID
    @Insert
    long insert(TaskLog log);
    
    // 更新日志
    @Update
    void update(TaskLog log);
    
    // 获取任务的所有日志（按时间倒序）
    @Query("SELECT * FROM task_logs WHERE task_id = :taskId ORDER BY start_time DESC")
    LiveData<List<TaskLog>> getLogsByTaskId(long taskId);
    
    // 获取任务的所有日志（同步，按时间倒序）
    @Query("SELECT * FROM task_logs WHERE task_id = :taskId ORDER BY start_time DESC")
    List<TaskLog> getLogsByTaskIdSync(long taskId);
    
    // 获取单条日志
    @Query("SELECT * FROM task_logs WHERE id = :logId")
    TaskLog getLogById(long logId);
    
    // 获取任务最新的进行中日志
    @Query("SELECT * FROM task_logs WHERE task_id = :taskId AND status = 0 ORDER BY start_time DESC LIMIT 1")
    TaskLog getInProgressLog(long taskId);
    
    // 删除30天前的日志
    @Query("DELETE FROM task_logs WHERE created_at < :timestamp")
    int deleteLogsOlderThan(long timestamp);
    
    // 获取日志数量
    @Query("SELECT COUNT(*) FROM task_logs WHERE task_id = :taskId")
    int getLogCountByTaskId(long taskId);
}
```

### 核心服务设计

#### PlaybackService（前台播放服务）

```java
public class PlaybackService extends Service {
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "playback_channel";
    
    private Map<Long, MediaPlayer> activePlayers = new HashMap<>();  // 支持多任务同时播放
    private Map<Long, Long> activeLogIds = new HashMap<>();          // 任务ID -> 日志ID映射
    private TaskRepository taskRepository;
    private TaskLogRepository logRepository;
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();
        long taskId = intent.getLongExtra("task_id", -1);
        
        if ("ACTION_START_PLAYBACK".equals(action)) {
            startPlayback(taskId);
        } else if ("ACTION_STOP_PLAYBACK".equals(action)) {
            stopPlayback(taskId);
        }
        
        return START_STICKY;  // 服务被杀死后自动重启
    }
    
    private void startPlayback(long taskId) {
        // 1. 创建日志记录（状态：进行中）
        long logId = logRepository.createLog(taskId);
        activeLogIds.put(taskId, logId);
        
        try {
            // 2. 获取任务和音频文件
            // 3. 创建 MediaPlayer
            // 4. 根据播放模式（顺序/随机）播放
            // 5. 更新前台通知
            // 6. 记录播放的文件到日志
        } catch (Exception e) {
            // 记录失败日志
            logRepository.updateLogFailed(logId, errorType, e.getMessage());
        }
    }
    
    private void stopPlayback(long taskId) {
        // 1. 停止并释放 MediaPlayer
        // 2. 更新日志记录（状态：成功，结束时间）
        Long logId = activeLogIds.remove(taskId);
        if (logId != null) {
            logRepository.updateLogSuccess(logId, playedFiles);
        }
        // 3. 从 activePlayers 移除
        // 4. 如果没有活动播放，停止前台服务
    }
    
    private void createNotificationChannel() {
        // 创建通知渠道（Android 8.0+）
    }
    
    private Notification buildNotification() {
        // 构建前台服务通知
    }
}
```

#### TaskLogRepository（日志仓库）

```java
public class TaskLogRepository {
    private TaskLogDao logDao;
    private ExecutorService executor;
    
    /**
     * 创建新的执行日志
     */
    public long createLog(long taskId) {
        TaskLog log = new TaskLog();
        log.setTaskId(taskId);
        log.setStartTime(System.currentTimeMillis());
        log.setStatus(LogStatus.IN_PROGRESS);
        log.setCreatedAt(System.currentTimeMillis());
        return logDao.insert(log);
    }
    
    /**
     * 更新日志为成功状态
     */
    public void updateLogSuccess(long logId, List<String> playedFiles) {
        TaskLog log = logDao.getLogById(logId);
        if (log != null) {
            log.setEndTime(System.currentTimeMillis());
            log.setStatus(LogStatus.SUCCESS);
            log.setPlayedFiles(new Gson().toJson(playedFiles));
            logDao.update(log);
        }
    }
    
    /**
     * 更新日志为失败状态
     */
    public void updateLogFailed(long logId, int errorType, String errorMessage) {
        TaskLog log = logDao.getLogById(logId);
        if (log != null) {
            log.setEndTime(System.currentTimeMillis());
            log.setStatus(LogStatus.FAILED);
            log.setErrorType(errorType);
            log.setErrorMessage(errorMessage);
            logDao.update(log);
        }
    }
    
    /**
     * 清理30天前的日志
     */
    public void cleanOldLogs() {
        long thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000);
        logDao.deleteLogsOlderThan(thirtyDaysAgo);
    }
    
    /**
     * 获取任务的日志列表
     */
    public LiveData<List<TaskLog>> getLogsByTaskId(long taskId) {
        return logDao.getLogsByTaskId(taskId);
    }
}
```

#### TaskSchedulerService（任务调度服务）

```java
public class TaskSchedulerService {
    private Context context;
    private AlarmManager alarmManager;
    private TaskRepository taskRepository;
    
    public void scheduleTask(Task task) {
        if (!task.isEnabled()) return;
        
        // 计算下次开始时间
        long startTime = calculateNextStartTime(task);
        long endTime = calculateNextEndTime(task);
        
        // 设置开始闹钟
        setAlarm(task.getId(), startTime, ACTION_START_PLAYBACK);
        
        // 设置结束闹钟
        setAlarm(task.getId(), endTime, ACTION_STOP_PLAYBACK);
    }
    
    public void cancelTask(long taskId) {
        // 取消该任务的所有闹钟
    }
    
    public void rescheduleAllTasks() {
        // 重新调度所有启用的任务（开机后调用）
    }
    
    private long calculateNextStartTime(Task task) {
        // 根据重复规则计算下次开始时间
        // 处理跨天情况
    }
    
    private void setAlarm(long taskId, long triggerTime, String action) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.setAction(action);
        intent.putExtra("task_id", taskId);
        
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
            context, 
            (int) taskId, 
            intent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        // 使用 setExactAndAllowWhileIdle 确保精确触发
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, 
            triggerTime, 
            pendingIntent
        );
    }
}
```

#### AlarmReceiver（闹钟接收器）

```java
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        long taskId = intent.getLongExtra("task_id", -1);
        
        Intent serviceIntent = new Intent(context, PlaybackService.class);
        serviceIntent.setAction(action);
        serviceIntent.putExtra("task_id", taskId);
        
        // Android 8.0+ 必须使用 startForegroundService
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
        
        // 如果是重复任务，设置下一次闹钟
        if ("ACTION_STOP_PLAYBACK".equals(action)) {
            TaskSchedulerService scheduler = new TaskSchedulerService(context);
            scheduler.scheduleNextOccurrence(taskId);
        }
    }
}
```

#### BootReceiver（开机接收器）

```java
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // 重新调度所有启用的任务
            TaskSchedulerService scheduler = new TaskSchedulerService(context);
            scheduler.rescheduleAllTasks();
        }
    }
}
```

### UI 设计

#### 页面结构

```
MainActivity (任务列表)
├── TaskListFragment
│   └── RecyclerView (任务卡片列表)
│       └── TaskAdapter
│           └── TaskViewHolder (单个任务卡片)
│
TaskEditActivity (任务编辑)
├── 任务名称输入
├── 时间范围选择器
│   ├── 开始时间 (TimePicker)
│   └── 结束时间 (TimePicker)
├── 播放模式选择 (RadioGroup)
├── 重复规则设置
│   ├── 重复类型 (Spinner)
│   └── 星期选择 (CheckBox 组)
├── 音频文件列表 (RecyclerView)
├── 添加音频按钮 → AudioPickerActivity
└── 查看日志按钮 → TaskLogActivity

TaskLogActivity (任务日志列表)
├── Toolbar (返回按钮、任务名称)
└── RecyclerView (日志列表)
    └── LogAdapter
        └── LogViewHolder (单条日志)
            ├── 执行时间 (开始 - 结束)
            ├── 执行状态 (成功/失败/进行中)
            ├── 播放文件数量
            └── 失败原因 (如有)

AudioPickerActivity (音频选择)
├── 文件浏览器 / SAF 选择器
└── 已选文件列表
```

#### 日志列表项设计

```
┌────────────────────────────────────────────┐
│ 2024-12-24 08:00:00 - 09:00:00    [成功] ✓ │
│ 播放了 5 个音频文件                         │
└────────────────────────────────────────────┘

┌────────────────────────────────────────────┐
│ 2024-12-23 08:00:00 - 08:05:32    [失败] ✗ │
│ 失败原因：文件缺失 - test.mp3 不存在        │
└────────────────────────────────────────────┘

┌────────────────────────────────────────────┐
│ 2024-12-24 14:00:00 - 进行中...   [播放中] │
│ 已播放 2 个音频文件                         │
└────────────────────────────────────────────┘
```

#### 任务卡片设计

```
┌────────────────────────────────────────────┐
│ [开关]  早间播放                     [更多] │
│         06:00 - 08:00                      │
│         每天 | 顺序播放                     │
│         3 个音频文件                        │
└────────────────────────────────────────────┘
```

### 权限配置

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />  <!-- Android 13+ -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<uses-permission android:name="android.permission.USE_EXACT_ALARM" />  <!-- Android 14+ -->
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />  <!-- Android 13+ -->

<application>
    <service
        android:name=".service.PlaybackService"
        android:foregroundServiceType="mediaPlayback"
        android:exported="false" />
    
    <receiver
        android:name=".receiver.AlarmReceiver"
        android:exported="false" />
    
    <receiver
        android:name=".receiver.BootReceiver"
        android:exported="true">
        <intent-filter>
            <action android:name="android.intent.action.BOOT_COMPLETED" />
        </intent-filter>
    </receiver>
</application>
```

## 关键流程

### 1. 任务创建流程

```
用户点击"新建任务"
    │
    ▼
打开 TaskEditActivity
    │
    ▼
用户填写任务信息
├── 输入任务名称
├── 选择时间范围
├── 选择播放模式
├── 设置重复规则
└── 选择音频文件
    │
    ▼
点击"保存"
    │
    ▼
TaskRepository.saveTask()
├── 保存 Task 到数据库
└── 保存 TaskAudioFile 到数据库
    │
    ▼
TaskSchedulerService.scheduleTask()
├── 计算下次触发时间
├── 设置开始闹钟
└── 设置结束闹钟
    │
    ▼
返回任务列表，显示新任务
```

### 2. 定时播放流程

```
AlarmManager 触发开始闹钟
    │
    ▼
AlarmReceiver.onReceive()
    │
    ▼
启动 PlaybackService (前台服务)
    │
    ▼
PlaybackService.startPlayback()
├── 显示前台通知
├── 获取任务音频文件列表
├── 创建 MediaPlayer
├── 根据播放模式播放
│   ├── 顺序模式：按 sortOrder 播放
│   └── 随机模式：打乱顺序播放
└── 循环播放直到结束时间
    │
    ▼
AlarmManager 触发结束闹钟
    │
    ▼
PlaybackService.stopPlayback()
├── 停止 MediaPlayer
├── 释放资源
└── 如果无其他活动任务，停止前台服务
    │
    ▼
TaskSchedulerService.scheduleNextOccurrence()
└── 如果是重复任务，设置下一次闹钟
```

### 4. 日志记录流程

```
任务开始播放
    │
    ▼
TaskLogRepository.createLog(taskId)
├── 创建日志记录
├── 状态 = IN_PROGRESS
├── 记录启动时间
└── 返回 logId
    │
    ▼
播放过程中
├── 记录播放的音频文件
└── 捕获异常信息
    │
    ▼
任务结束/异常
    │
    ├── 正常结束 ──────────────────────┐
    │                                  ▼
    │                    TaskLogRepository.updateLogSuccess()
    │                    ├── 状态 = SUCCESS
    │                    ├── 记录结束时间
    │                    └── 记录播放文件列表
    │
    └── 异常结束 ──────────────────────┐
                                       ▼
                         TaskLogRepository.updateLogFailed()
                         ├── 状态 = FAILED
                         ├── 记录结束时间
                         ├── 记录错误类型
                         └── 记录错误信息
```

### 5. 日志清理流程

```
应用启动 / 每日定时
    │
    ▼
TaskLogRepository.cleanOldLogs()
    │
    ▼
计算 30 天前的时间戳
    │
    ▼
DELETE FROM task_logs WHERE created_at < 时间戳
    │
    ▼
清理完成
```

### 3. 多任务混音流程

```
任务A 开始播放 (06:00)
    │
    ▼
PlaybackService 创建 MediaPlayer A
activePlayers = {taskA: playerA}
    │
    ▼
任务B 开始播放 (06:30，与A重叠)
    │
    ▼
PlaybackService 创建 MediaPlayer B
activePlayers = {taskA: playerA, taskB: playerB}
    │
    ▼
两个 MediaPlayer 同时播放（系统自动混音）
    │
    ▼
任务A 结束 (08:00)
    │
    ▼
停止 playerA，从 activePlayers 移除
activePlayers = {taskB: playerB}
    │
    ▼
任务B 结束 (09:00)
    │
    ▼
停止 playerB，停止前台服务
```

## 安全考虑

1. **文件访问安全**
   - 使用 Storage Access Framework (SAF) 选择文件
   - 持久化 URI 权限：`contentResolver.takePersistableUriPermission()`
   - 验证文件存在性后再播放

2. **服务安全**
   - PlaybackService 设置 `exported="false"`
   - 使用 PendingIntent.FLAG_IMMUTABLE

3. **数据安全**
   - Room 自动处理 SQL 注入防护
   - 使用参数化查询

## 测试策略

### 单元测试

- TaskRepository CRUD 操作
- 时间计算逻辑（跨天、重复规则）
- 播放模式逻辑（顺序/随机）

### 集成测试

- 数据库迁移测试
- AlarmManager 调度测试
- 前台服务生命周期测试

### 功能测试

| 测试场景 | 预期结果 |
|----------|----------|
| 创建任务并保存 | 任务出现在列表，闹钟已设置 |
| 到达开始时间 | 自动开始播放，显示通知 |
| 到达结束时间 | 自动停止播放 |
| 熄屏状态播放 | 正常播放不中断 |
| 两任务时间重叠 | 两个音频同时播放 |
| 手机重启 | 任务自动恢复调度 |
| 音频文件被删除 | 跳过该文件，继续播放下一个 |
| 查看任务日志 | 显示日志列表，最新在前 |
| 日志超过30天 | 自动清理过期日志 |

## 项目结构

```
app/
├── src/main/java/com/example/noiseretaliation/
│   ├── MainActivity.java
│   ├── activity/
│   │   ├── TaskEditActivity.java
│   │   ├── TaskLogActivity.java          // 新增：日志列表页面
│   │   └── AudioPickerActivity.java
│   ├── adapter/
│   │   ├── TaskAdapter.java
│   │   ├── TaskLogAdapter.java           // 新增：日志列表适配器
│   │   └── AudioFileAdapter.java
│   ├── database/
│   │   ├── AppDatabase.java
│   │   ├── dao/
│   │   │   ├── TaskDao.java
│   │   │   ├── TaskAudioFileDao.java
│   │   │   └── TaskLogDao.java           // 新增：日志DAO
│   │   └── entity/
│   │       ├── Task.java
│   │       ├── TaskAudioFile.java
│   │       └── TaskLog.java              // 新增：日志实体
│   ├── repository/
│   │   ├── TaskRepository.java
│   │   └── TaskLogRepository.java        // 新增：日志仓库
│   ├── service/
│   │   ├── PlaybackService.java
│   │   └── TaskSchedulerService.java
│   ├── receiver/
│   │   ├── AlarmReceiver.java
│   │   └── BootReceiver.java
│   └── util/
│       ├── TimeUtils.java
│       ├── LogStatus.java                // 新增：日志状态常量
│       ├── LogErrorType.java             // 新增：错误类型常量
│       └── PermissionUtils.java
├── src/main/res/
│   ├── layout/
│   │   ├── activity_main.xml
│   │   ├── activity_task_edit.xml
│   │   ├── activity_task_log.xml         // 新增：日志列表布局
│   │   ├── activity_audio_picker.xml
│   │   ├── item_task.xml
│   │   └── item_task_log.xml             // 新增：日志列表项布局
│   ├── values/
│   │   ├── strings.xml
│   │   └── colors.xml
│   └── drawable/
└── build.gradle
```

## 依赖配置

```groovy
// app/build.gradle
dependencies {
    // Room 数据库
    implementation "androidx.room:room-runtime:2.6.1"
    annotationProcessor "androidx.room:room-compiler:2.6.1"
    
    // Material Design
    implementation "com.google.android.material:material:1.11.0"
    
    // RecyclerView
    implementation "androidx.recyclerview:recyclerview:1.3.2"
    
    // ConstraintLayout
    implementation "androidx.constraintlayout:constraintlayout:2.1.4"
}
```
