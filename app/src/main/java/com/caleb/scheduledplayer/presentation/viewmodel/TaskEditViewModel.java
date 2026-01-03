package com.caleb.scheduledplayer.presentation.viewmodel;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.caleb.scheduledplayer.ScheduledPlayerApp;
import com.caleb.scheduledplayer.data.dao.TaskDao;
import com.caleb.scheduledplayer.data.entity.TaskEntity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 任务编辑 ViewModel
 */
public class TaskEditViewModel extends AndroidViewModel {

    private final TaskDao taskDao;
    private final ExecutorService executor;
    private final Handler mainHandler;

    private final MutableLiveData<TaskEntity> task = new MutableLiveData<>();
    private final MutableLiveData<Boolean> saveResult = new MutableLiveData<>();

    public TaskEditViewModel(@NonNull Application application) {
        super(application);
        taskDao = ((ScheduledPlayerApp) application).getDatabase().taskDao();
        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 获取任务
     */
    public LiveData<TaskEntity> getTask() {
        return task;
    }

    /**
     * 获取保存结果
     */
    public LiveData<Boolean> getSaveResult() {
        return saveResult;
    }

    /**
     * 加载任务
     */
    public void loadTask(long taskId) {
        executor.execute(() -> {
            TaskEntity entity = taskDao.getTaskByIdSync(taskId);
            task.postValue(entity);
        });
    }

    /**
     * 保存任务
     */
    public void saveTask(TaskEntity taskEntity) {
        saveTask(taskEntity, null);
    }

    /**
     * 保存任务（带回调）
     */
    public void saveTask(TaskEntity taskEntity, OnTaskSavedCallback callback) {
        executor.execute(() -> {
            try {
                long taskId;
                if (taskEntity.getId() > 0) {
                    taskDao.update(taskEntity);
                    taskId = taskEntity.getId();
                } else {
                    taskId = taskDao.insert(taskEntity);
                }
                
                // 在主线程回调
                if (callback != null) {
                    final long savedId = taskId;
                    mainHandler.post(() -> callback.onTaskSaved(savedId));
                }
                
                saveResult.postValue(true);
            } catch (Exception e) {
                e.printStackTrace();
                saveResult.postValue(false);
            }
        });
    }
    
    /**
     * 静默保存任务（不触发 saveResult LiveData，用于自动保存）
     */
    public void saveTaskSilently(TaskEntity taskEntity, OnTaskSavedCallback callback) {
        executor.execute(() -> {
            try {
                long taskId;
                if (taskEntity.getId() > 0) {
                    taskDao.update(taskEntity);
                    taskId = taskEntity.getId();
                } else {
                    taskId = taskDao.insert(taskEntity);
                }
                
                // 在主线程回调
                if (callback != null) {
                    final long savedId = taskId;
                    mainHandler.post(() -> callback.onTaskSaved(savedId));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * 删除任务
     */
    public void deleteTask(long taskId) {
        executor.execute(() -> taskDao.deleteById(taskId));
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        executor.shutdown();
    }

    /**
     * 任务保存回调接口
     */
    public interface OnTaskSavedCallback {
        void onTaskSaved(long taskId);
    }
}
