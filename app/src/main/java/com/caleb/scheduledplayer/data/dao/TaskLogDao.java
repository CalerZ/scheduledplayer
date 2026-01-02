package com.caleb.scheduledplayer.data.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.caleb.scheduledplayer.data.entity.TaskLogEntity;

import java.util.List;

/**
 * 任务执行日志数据访问对象
 */
@Dao
public interface TaskLogDao {

    /**
     * 插入新日志，返回日志ID
     */
    @Insert
    long insert(TaskLogEntity log);

    /**
     * 更新日志
     */
    @Update
    void update(TaskLogEntity log);

    /**
     * 获取任务的所有日志（按时间倒序，LiveData）
     */
    @Query("SELECT * FROM task_logs WHERE task_id = :taskId ORDER BY start_time DESC")
    LiveData<List<TaskLogEntity>> getLogsByTaskId(long taskId);

    /**
     * 获取任务的所有日志（同步，按时间倒序）
     */
    @Query("SELECT * FROM task_logs WHERE task_id = :taskId ORDER BY start_time DESC")
    List<TaskLogEntity> getLogsByTaskIdSync(long taskId);

    /**
     * 获取任务的日志（分页）
     */
    @Query("SELECT * FROM task_logs WHERE task_id = :taskId ORDER BY start_time DESC LIMIT :limit OFFSET :offset")
    List<TaskLogEntity> getLogsByTaskIdPaged(long taskId, int limit, int offset);

    /**
     * 获取单条日志
     */
    @Query("SELECT * FROM task_logs WHERE id = :logId")
    TaskLogEntity getLogById(long logId);

    /**
     * 获取任务最新的进行中日志
     */
    @Query("SELECT * FROM task_logs WHERE task_id = :taskId AND status = 0 ORDER BY start_time DESC LIMIT 1")
    TaskLogEntity getInProgressLog(long taskId);

    /**
     * 删除指定时间戳之前的日志
     * @param timestamp 时间戳（毫秒）
     * @return 删除的记录数
     */
    @Query("DELETE FROM task_logs WHERE created_at < :timestamp")
    int deleteLogsOlderThan(long timestamp);

    /**
     * 获取任务的日志数量
     */
    @Query("SELECT COUNT(*) FROM task_logs WHERE task_id = :taskId")
    int getLogCountByTaskId(long taskId);

    /**
     * 获取所有日志数量
     */
    @Query("SELECT COUNT(*) FROM task_logs")
    int getTotalLogCount();

    /**
     * 删除任务的所有日志
     */
    @Query("DELETE FROM task_logs WHERE task_id = :taskId")
    void deleteLogsByTaskId(long taskId);

    /**
     * 删除所有日志（用于测试）
     */
    @Query("DELETE FROM task_logs")
    void deleteAll();

    /**
     * 获取最近的日志记录
     */
    @Query("SELECT * FROM task_logs ORDER BY start_time DESC LIMIT :limit")
    List<TaskLogEntity> getRecentLogs(int limit);

    /**
     * 获取指定状态的日志数量
     */
    @Query("SELECT COUNT(*) FROM task_logs WHERE task_id = :taskId AND status = :status")
    int getLogCountByStatus(long taskId, int status);

    /**
     * 获取任务所有进行中的日志
     */
    @Query("SELECT * FROM task_logs WHERE task_id = :taskId AND status = 0")
    List<TaskLogEntity> getInProgressLogs(long taskId);
}
