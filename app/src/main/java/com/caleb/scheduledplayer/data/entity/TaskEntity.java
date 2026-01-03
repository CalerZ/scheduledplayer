package com.caleb.scheduledplayer.data.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 任务实体类
 * 对应数据库中的 tasks 表
 */
@Entity(tableName = "tasks")
public class TaskEntity {

    @PrimaryKey(autoGenerate = true)
    private long id;

    /**
     * 任务名称
     */
    @NonNull
    @ColumnInfo(name = "name")
    private String name;

    /**
     * 是否启用
     */
    @ColumnInfo(name = "enabled")
    private boolean enabled;

    /**
     * 播放开始时间 (格式: HH:mm)
     */
    @NonNull
    @ColumnInfo(name = "start_time")
    private String startTime;

    /**
     * 播放结束时间 (格式: HH:mm)
     */
    @NonNull
    @ColumnInfo(name = "end_time")
    private String endTime;

    /**
     * 音频文件路径列表 (JSON 格式存储)
     */
    @ColumnInfo(name = "audio_paths")
    private String audioPaths;

    /**
     * 播放模式: 0=顺序, 1=随机, 2=循环
     */
    @ColumnInfo(name = "play_mode")
    private int playMode;

    /**
     * 音量 (0-100)
     */
    @ColumnInfo(name = "volume")
    private int volume;

    /**
     * 重复规则 (星期几, 使用位运算: 周一=1, 周二=2, 周三=4...)
     */
    @ColumnInfo(name = "repeat_days")
    private int repeatDays;

    /**
     * 创建时间
     */
    @ColumnInfo(name = "created_at")
    private long createdAt;

    /**
     * 更新时间
     */
    @ColumnInfo(name = "updated_at")
    private long updatedAt;

    /**
     * 播放设备: 0=默认(扬声器), 1=仅蓝牙
     */
    @ColumnInfo(name = "output_device", defaultValue = "0")
    private int outputDevice;

    /**
     * 全天播放模式：启用后忽略结束时间，持续播放
     */
    @ColumnInfo(name = "all_day_play", defaultValue = "0")
    private boolean allDayPlay;

    // 播放模式常量
    public static final int PLAY_MODE_SEQUENCE = 0;
    public static final int PLAY_MODE_RANDOM = 1;
    public static final int PLAY_MODE_LOOP = 2;

    // 播放设备常量
    public static final int OUTPUT_DEVICE_DEFAULT = 0;
    public static final int OUTPUT_DEVICE_BLUETOOTH = 1;

    // 星期常量 (用于 repeatDays 位运算)
    public static final int MONDAY = 1;
    public static final int TUESDAY = 2;
    public static final int WEDNESDAY = 4;
    public static final int THURSDAY = 8;
    public static final int FRIDAY = 16;
    public static final int SATURDAY = 32;
    public static final int SUNDAY = 64;
    public static final int EVERYDAY = 127;

    // Getters and Setters
    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    @NonNull
    public String getName() {
        return name;
    }

    public void setName(@NonNull String name) {
        this.name = name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @NonNull
    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(@NonNull String startTime) {
        this.startTime = startTime;
    }

    @NonNull
    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(@NonNull String endTime) {
        this.endTime = endTime;
    }

    public String getAudioPaths() {
        return audioPaths;
    }

    public void setAudioPaths(String audioPaths) {
        this.audioPaths = audioPaths;
    }

    public int getPlayMode() {
        return playMode;
    }

    public void setPlayMode(int playMode) {
        this.playMode = playMode;
    }

    public int getVolume() {
        return volume;
    }

    public void setVolume(int volume) {
        this.volume = volume;
    }

    public int getRepeatDays() {
        return repeatDays;
    }

    public void setRepeatDays(int repeatDays) {
        this.repeatDays = repeatDays;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public int getOutputDevice() {
        return outputDevice;
    }

    public void setOutputDevice(int outputDevice) {
        this.outputDevice = outputDevice;
    }

    public boolean isAllDayPlay() {
        return allDayPlay;
    }

    public void setAllDayPlay(boolean allDayPlay) {
        this.allDayPlay = allDayPlay;
    }

    /**
     * 检查指定星期是否需要执行
     */
    public boolean shouldRunOnDay(int dayFlag) {
        return (repeatDays & dayFlag) != 0;
    }
}
