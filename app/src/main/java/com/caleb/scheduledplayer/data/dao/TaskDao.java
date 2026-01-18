package com.caleb.scheduledplayer.data.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.caleb.scheduledplayer.data.entity.TaskEntity;

import java.util.List;

/**
 * 任务数据访问对象
 */
@Dao
public interface TaskDao {

    /**
     * 插入任务
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(TaskEntity task);

    /**
     * 更新任务
     */
    @Update
    void update(TaskEntity task);

    /**
     * 删除任务
     */
    @Delete
    void delete(TaskEntity task);

    /**
     * 根据 ID 删除任务
     */
    @Query("DELETE FROM tasks WHERE id = :taskId")
    void deleteById(long taskId);

    /**
     * 获取所有任务 (LiveData)
     */
    @Query("SELECT * FROM tasks ORDER BY created_at DESC")
    LiveData<List<TaskEntity>> getAllTasks();

    /**
     * 获取所有任务 (同步)
     */
    @Query("SELECT * FROM tasks ORDER BY created_at DESC")
    List<TaskEntity> getAllTasksSync();

    /**
     * 根据 ID 获取任务
     */
    @Query("SELECT * FROM tasks WHERE id = :taskId")
    LiveData<TaskEntity> getTaskById(long taskId);

    /**
     * 根据 ID 获取任务 (同步)
     */
    @Query("SELECT * FROM tasks WHERE id = :taskId")
    TaskEntity getTaskByIdSync(long taskId);

    /**
     * 获取所有启用的任务
     */
    @Query("SELECT * FROM tasks WHERE enabled = 1 ORDER BY start_time ASC")
    LiveData<List<TaskEntity>> getEnabledTasks();

    /**
     * 获取所有启用的任务 (同步)
     */
    @Query("SELECT * FROM tasks WHERE enabled = 1 ORDER BY start_time ASC")
    List<TaskEntity> getEnabledTasksSync();

    /**
     * 更新任务启用状态
     */
    @Query("UPDATE tasks SET enabled = :enabled, updated_at = :updatedAt WHERE id = :taskId")
    void updateEnabled(long taskId, boolean enabled, long updatedAt);

    /**
     * 获取任务数量
     */
    @Query("SELECT COUNT(*) FROM tasks")
    int getTaskCount();

    /**
     * 删除所有任务（用于测试）
     */
    @Query("DELETE FROM tasks")
    void deleteAll();

    // ==================== 执行状态相关方法 ====================

    /**
     * 更新任务执行状态
     * @param taskId 任务ID
     * @param executionState 执行状态值
     * @param updatedAt 更新时间
     */
    @Query("UPDATE tasks SET execution_state = :executionState, updated_at = :updatedAt WHERE id = :taskId")
    void updateExecutionState(long taskId, int executionState, long updatedAt);

    /**
     * 更新任务执行时间信息
     * @param taskId 任务ID
     * @param executionStart 执行开始时间戳
     * @param executionEnd 执行结束时间戳
     * @param updatedAt 更新时间
     */
    @Query("UPDATE tasks SET current_execution_start = :executionStart, current_execution_end = :executionEnd, updated_at = :updatedAt WHERE id = :taskId")
    void updateExecutionTimes(long taskId, long executionStart, long executionEnd, long updatedAt);

    /**
     * 更新任务执行状态和时间信息（合并操作）
     * @param taskId 任务ID
     * @param executionState 执行状态值
     * @param executionStart 执行开始时间戳
     * @param executionEnd 执行结束时间戳
     * @param updatedAt 更新时间
     */
    @Query("UPDATE tasks SET execution_state = :executionState, current_execution_start = :executionStart, current_execution_end = :executionEnd, updated_at = :updatedAt WHERE id = :taskId")
    void updateExecutionInfo(long taskId, int executionState, long executionStart, long executionEnd, long updatedAt);

    /**
     * 重置任务执行状态（用于任务编辑或重新启用时）
     * @param taskId 任务ID
     * @param updatedAt 更新时间
     */
    @Query("UPDATE tasks SET execution_state = 0, current_execution_start = 0, current_execution_end = 0, updated_at = :updatedAt WHERE id = :taskId")
    void resetExecutionState(long taskId, long updatedAt);

    /**
     * 根据执行状态获取任务
     * @param executionState 执行状态值
     */
    @Query("SELECT * FROM tasks WHERE execution_state = :executionState")
    List<TaskEntity> getTasksByExecutionState(int executionState);

    /**
     * 获取所有正在执行或暂停的任务
     */
    @Query("SELECT * FROM tasks WHERE execution_state = 2 OR execution_state = 3")
    List<TaskEntity> getActiveTasks();

    /**
     * 更新任务启用状态并重置执行状态
     * @param taskId 任务ID
     * @param enabled 是否启用
     * @param updatedAt 更新时间
     */
    @Query("UPDATE tasks SET enabled = :enabled, execution_state = 0, current_execution_start = 0, current_execution_end = 0, updated_at = :updatedAt WHERE id = :taskId")
    void updateEnabledAndResetState(long taskId, boolean enabled, long updatedAt);

    /**
     * 禁用任务并设置状态为 DISABLED
     * @param taskId 任务ID
     * @param updatedAt 更新时间
     */
    @Query("UPDATE tasks SET enabled = 0, execution_state = 5, current_execution_start = 0, current_execution_end = 0, updated_at = :updatedAt WHERE id = :taskId")
    void disableTaskWithState(long taskId, long updatedAt);

    /**
     * 只更新执行结束时间（用于全天播放任务的午夜检查）
     * 避免使用整体更新覆盖用户可能修改的其他字段
     * @param taskId 任务ID
     * @param executionEnd 新的执行结束时间戳
     * @param updatedAt 更新时间
     */
    @Query("UPDATE tasks SET current_execution_end = :executionEnd, updated_at = :updatedAt WHERE id = :taskId")
    void updateExecutionEndTime(long taskId, long executionEnd, long updatedAt);

    // ==================== 并发控制相关方法 ====================

    /**
     * 获取当前正在执行的任务数量（用于并发播放限制检查）
     * execution_state = 2 表示 EXECUTING 状态
     */
    @Query("SELECT COUNT(*) FROM tasks WHERE execution_state = 2")
    int getExecutingTaskCount();

    /**
     * 获取当前正在执行的任务数量（LiveData 版本，用于 UI 观察）
     */
    @Query("SELECT COUNT(*) FROM tasks WHERE execution_state = 2")
    LiveData<Integer> getExecutingTaskCountLive();

    /**
     * 获取所有等待空位的任务
     * execution_state = 7 表示 WAITING_SLOT 状态
     */
    @Query("SELECT * FROM tasks WHERE execution_state = 7 ORDER BY current_execution_start ASC")
    List<TaskEntity> getWaitingSlotTasks();
}
