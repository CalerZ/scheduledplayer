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
}
