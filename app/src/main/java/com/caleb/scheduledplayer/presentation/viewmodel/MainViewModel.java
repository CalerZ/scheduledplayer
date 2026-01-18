package com.caleb.scheduledplayer.presentation.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import com.caleb.scheduledplayer.ScheduledPlayerApp;
import com.caleb.scheduledplayer.data.dao.TaskDao;
import com.caleb.scheduledplayer.data.entity.TaskEntity;
import com.caleb.scheduledplayer.service.player.AudioPlaybackService;
import com.caleb.scheduledplayer.service.scheduler.TaskScheduleManager;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主界面 ViewModel
 */
public class MainViewModel extends AndroidViewModel {

    private final TaskDao taskDao;
    private final LiveData<List<TaskEntity>> tasks;
    private final ExecutorService executor;
    private final TaskScheduleManager scheduleManager;

    public MainViewModel(@NonNull Application application) {
        super(application);
        taskDao = ((ScheduledPlayerApp) application).getDatabase().taskDao();
        tasks = taskDao.getAllTasks();
        executor = Executors.newSingleThreadExecutor();
        scheduleManager = TaskScheduleManager.getInstance(application);
    }

    /**
     * 获取所有任务
     */
    public LiveData<List<TaskEntity>> getTasks() {
        return tasks;
    }

    /**
     * 更新任务启用状态
     */
    public void updateTaskEnabled(long taskId, boolean enabled) {
        executor.execute(() -> {
            // 使用新接口：启用时重置执行状态
            taskDao.updateEnabledAndResetState(taskId, enabled, System.currentTimeMillis());
            
            // 更新闹钟调度
            TaskEntity task = taskDao.getTaskByIdSync(taskId);
            if (task != null) {
                if (enabled) {
                    scheduleManager.scheduleTask(task);
                } else {
                    scheduleManager.cancelTask(taskId);
                    // 停止正在播放的音乐
                    AudioPlaybackService.stopTaskPlayback(getApplication(), taskId);
                }
            }
        });
    }

    /**
     * 删除任务
     */
    public void deleteTask(TaskEntity task) {
        executor.execute(() -> {
            // 先取消调度
            scheduleManager.cancelTask(task.getId());
            // 停止正在播放的音乐
            AudioPlaybackService.stopTaskPlayback(getApplication(), task.getId());
            // 再删除任务
            taskDao.delete(task);
        });
    }

    /**
     * 根据 ID 删除任务
     */
    public void deleteTaskById(long taskId) {
        executor.execute(() -> {
            // 先取消调度
            scheduleManager.cancelTask(taskId);
            // 停止正在播放的音乐
            AudioPlaybackService.stopTaskPlayback(getApplication(), taskId);
            // 再删除任务
            taskDao.deleteById(taskId);
        });
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        executor.shutdown();
    }
}
