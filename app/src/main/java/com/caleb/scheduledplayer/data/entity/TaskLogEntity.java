package com.caleb.scheduledplayer.data.entity;

import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 任务执行日志实体类
 * 记录每次任务执行的详细信息
 */
@Entity(tableName = "task_logs",
        foreignKeys = @ForeignKey(
                entity = TaskEntity.class,
                parentColumns = "id",
                childColumns = "task_id",
                onDelete = ForeignKey.CASCADE
        ),
        indices = {
                @Index(value = "task_id"),
                @Index(value = "start_time"),
                @Index(value = "created_at")
        })
public class TaskLogEntity {

    @PrimaryKey(autoGenerate = true)
    private long id;

    /**
     * 关联的任务ID
     */
    @ColumnInfo(name = "task_id")
    private long taskId;

    /**
     * 任务启动时间（时间戳）
     */
    @ColumnInfo(name = "start_time")
    private long startTime;

    /**
     * 任务结束时间（时间戳，可为空表示进行中）
     */
    @Nullable
    @ColumnInfo(name = "end_time")
    private Long endTime;

    /**
     * 执行状态: 0=进行中, 1=成功, 2=失败
     * @see com.caleb.scheduledplayer.util.LogStatus
     */
    @ColumnInfo(name = "status")
    private int status;

    /**
     * 播放的音频文件列表（JSON数组格式）
     */
    @Nullable
    @ColumnInfo(name = "played_files")
    private String playedFiles;

    /**
     * 错误类型: 1=文件缺失, 2=权限问题, 3=播放器错误, 4=其他
     * @see com.caleb.scheduledplayer.util.LogErrorType
     */
    @Nullable
    @ColumnInfo(name = "error_type")
    private Integer errorType;

    /**
     * 错误详细信息
     */
    @Nullable
    @ColumnInfo(name = "error_message")
    private String errorMessage;

    /**
     * 记录创建时间（用于清理过期日志）
     */
    @ColumnInfo(name = "created_at")
    private long createdAt;

    // Getters and Setters
    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getTaskId() {
        return taskId;
    }

    public void setTaskId(long taskId) {
        this.taskId = taskId;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    @Nullable
    public Long getEndTime() {
        return endTime;
    }

    public void setEndTime(@Nullable Long endTime) {
        this.endTime = endTime;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    @Nullable
    public String getPlayedFiles() {
        return playedFiles;
    }

    public void setPlayedFiles(@Nullable String playedFiles) {
        this.playedFiles = playedFiles;
    }

    @Nullable
    public Integer getErrorType() {
        return errorType;
    }

    public void setErrorType(@Nullable Integer errorType) {
        this.errorType = errorType;
    }

    @Nullable
    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(@Nullable String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * 计算执行时长（毫秒）
     * @return 执行时长，如果还在进行中则返回到当前的时长
     */
    public long getDuration() {
        if (endTime != null) {
            return endTime - startTime;
        }
        return System.currentTimeMillis() - startTime;
    }

    /**
     * 判断是否执行成功
     */
    public boolean isSuccess() {
        return status == com.caleb.scheduledplayer.util.LogStatus.SUCCESS;
    }

    /**
     * 判断是否执行失败
     */
    public boolean isFailed() {
        return status == com.caleb.scheduledplayer.util.LogStatus.FAILED;
    }

    /**
     * 判断是否正在执行
     */
    public boolean isInProgress() {
        return status == com.caleb.scheduledplayer.util.LogStatus.IN_PROGRESS;
    }
}
