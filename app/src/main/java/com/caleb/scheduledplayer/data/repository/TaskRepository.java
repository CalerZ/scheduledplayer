package com.caleb.scheduledplayer.data.repository;

import android.app.Application;

import androidx.lifecycle.LiveData;

import com.caleb.scheduledplayer.data.dao.TaskDao;
import com.caleb.scheduledplayer.data.database.AppDatabase;
import com.caleb.scheduledplayer.data.entity.TaskEntity;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 任务仓库类
 * 封装数据库操作，提供数据访问接口
 */
public class TaskRepository {

    private final TaskDao taskDao;
    private final ExecutorService executorService;
    private final LiveData<List<TaskEntity>> allTasks;
    private final LiveData<List<TaskEntity>> enabledTasks;

    public TaskRepository(Application application) {
        AppDatabase database = AppDatabase.getInstance(application);
        taskDao = database.taskDao();
        executorService = Executors.newFixedThreadPool(2);
        allTasks = taskDao.getAllTasks();
        enabledTasks = taskDao.getEnabledTasks();
    }

    /**
     * 获取所有任务 (LiveData)
     */
    public LiveData<List<TaskEntity>> getAllTasks() {
        return allTasks;
    }

    /**
     * 获取所有启用的任务 (LiveData)
     */
    public LiveData<List<TaskEntity>> getEnabledTasks() {
        return enabledTasks;
    }

    /**
     * 根据 ID 获取任务 (LiveData)
     */
    public LiveData<TaskEntity> getTaskById(long taskId) {
        return taskDao.getTaskById(taskId);
    }

    /**
     * 根据 ID 获取任务 (同步)
     */
    public TaskEntity getTaskByIdSync(long taskId) {
        return taskDao.getTaskByIdSync(taskId);
    }

    /**
     * 获取所有启用的任务 (同步)
     */
    public List<TaskEntity> getEnabledTasksSync() {
        return taskDao.getEnabledTasksSync();
    }

    /**
     * 插入任务
     */
    public void insert(TaskEntity task, OnTaskInsertedCallback callback) {
        executorService.execute(() -> {
            task.setCreatedAt(System.currentTimeMillis());
            task.setUpdatedAt(System.currentTimeMillis());
            long id = taskDao.insert(task);
            if (callback != null) {
                callback.onTaskInserted(id);
            }
        });
    }

    /**
     * 插入任务 (同步)
     */
    public long insertSync(TaskEntity task) {
        task.setCreatedAt(System.currentTimeMillis());
        task.setUpdatedAt(System.currentTimeMillis());
        return taskDao.insert(task);
    }

    /**
     * 更新任务
     */
    public void update(TaskEntity task) {
        executorService.execute(() -> {
            task.setUpdatedAt(System.currentTimeMillis());
            taskDao.update(task);
        });
    }

    /**
     * 更新任务 (同步)
     */
    public void updateSync(TaskEntity task) {
        task.setUpdatedAt(System.currentTimeMillis());
        taskDao.update(task);
    }

    /**
     * 删除任务
     */
    public void delete(TaskEntity task) {
        executorService.execute(() -> taskDao.delete(task));
    }

    /**
     * 根据 ID 删除任务
     */
    public void deleteById(long taskId) {
        executorService.execute(() -> taskDao.deleteById(taskId));
    }

    /**
     * 更新任务启用状态
     */
    public void updateEnabled(long taskId, boolean enabled) {
        executorService.execute(() -> 
            taskDao.updateEnabled(taskId, enabled, System.currentTimeMillis())
        );
    }

    /**
     * 更新任务启用状态 (同步)
     */
    public void updateEnabledSync(long taskId, boolean enabled) {
        taskDao.updateEnabled(taskId, enabled, System.currentTimeMillis());
    }

    /**
     * 获取任务数量
     */
    public int getTaskCount() {
        return taskDao.getTaskCount();
    }

    /**
     * 任务插入回调接口
     */
    public interface OnTaskInsertedCallback {
        void onTaskInserted(long taskId);
    }
}
