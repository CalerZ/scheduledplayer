package com.caleb.scheduledplayer.data.repository;

import android.app.Application;
import android.util.Log;

import androidx.lifecycle.LiveData;

import com.caleb.scheduledplayer.data.dao.TaskLogDao;
import com.caleb.scheduledplayer.data.database.AppDatabase;
import com.caleb.scheduledplayer.data.entity.TaskLogEntity;
import com.caleb.scheduledplayer.util.LogErrorType;
import com.caleb.scheduledplayer.util.LogStatus;

import org.json.JSONArray;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 任务执行日志仓库
 * 封装日志的增删改查操作
 */
public class TaskLogRepository {

    private static final String TAG = "TaskLogRepository";
    
    /**
     * 30天的毫秒数
     */
    private static final long THIRTY_DAYS_MILLIS = 30L * 24 * 60 * 60 * 1000;

    private final TaskLogDao logDao;
    private final ExecutorService executor;

    public TaskLogRepository(Application application) {
        AppDatabase db = AppDatabase.getInstance(application);
        logDao = db.taskLogDao();
        executor = Executors.newSingleThreadExecutor();
    }

    /**
     * 创建新的执行日志（任务开始时调用）
     * @param taskId 任务ID
     * @return 日志ID
     */
    public long createLog(long taskId) {
        // 先关闭该任务所有进行中的旧日志（防止 Service 被杀死后日志状态不一致）
        closeInProgressLogs(taskId);
        
        TaskLogEntity log = new TaskLogEntity();
        log.setTaskId(taskId);
        log.setStartTime(System.currentTimeMillis());
        log.setStatus(LogStatus.IN_PROGRESS);
        log.setCreatedAt(System.currentTimeMillis());
        
        long logId = logDao.insert(log);
        Log.d(TAG, "Created log for task " + taskId + ", logId: " + logId);
        return logId;
    }

    /**
     * 关闭任务所有进行中的日志（标记为成功，但没有播放文件记录）
     */
    private void closeInProgressLogs(long taskId) {
        List<TaskLogEntity> inProgressLogs = logDao.getInProgressLogs(taskId);
        for (TaskLogEntity log : inProgressLogs) {
            log.setEndTime(System.currentTimeMillis());
            log.setStatus(LogStatus.SUCCESS);
            logDao.update(log);
            Log.d(TAG, "Closed orphan in-progress log: " + log.getId());
        }
    }

    /**
     * 异步创建日志
     */
    public void createLogAsync(long taskId, LogCallback callback) {
        executor.execute(() -> {
            long logId = createLog(taskId);
            if (callback != null) {
                callback.onLogCreated(logId);
            }
        });
    }

    /**
     * 更新日志为成功状态
     * @param logId 日志ID
     * @param playedFiles 播放的文件列表
     */
    public void updateLogSuccess(long logId, List<String> playedFiles) {
        TaskLogEntity log = logDao.getLogById(logId);
        if (log != null) {
            log.setEndTime(System.currentTimeMillis());
            log.setStatus(LogStatus.SUCCESS);
            log.setPlayedFiles(toJsonArray(playedFiles));
            logDao.update(log);
            Log.d(TAG, "Updated log " + logId + " to SUCCESS");
        } else {
            Log.w(TAG, "Log not found: " + logId);
        }
    }

    /**
     * 异步更新日志为成功状态
     */
    public void updateLogSuccessAsync(long logId, List<String> playedFiles) {
        executor.execute(() -> updateLogSuccess(logId, playedFiles));
    }

    /**
     * 更新日志为失败状态
     * @param logId 日志ID
     * @param errorType 错误类型
     * @param errorMessage 错误信息
     */
    public void updateLogFailed(long logId, int errorType, String errorMessage) {
        TaskLogEntity log = logDao.getLogById(logId);
        if (log != null) {
            log.setEndTime(System.currentTimeMillis());
            log.setStatus(LogStatus.FAILED);
            log.setErrorType(errorType);
            log.setErrorMessage(errorMessage);
            logDao.update(log);
            Log.d(TAG, "Updated log " + logId + " to FAILED: " + errorMessage);
        } else {
            Log.w(TAG, "Log not found: " + logId);
        }
    }

    /**
     * 异步更新日志为失败状态
     */
    public void updateLogFailedAsync(long logId, int errorType, String errorMessage) {
        executor.execute(() -> updateLogFailed(logId, errorType, errorMessage));
    }

    /**
     * 更新播放的文件列表（进行中状态）
     */
    public void updatePlayedFiles(long logId, List<String> playedFiles) {
        TaskLogEntity log = logDao.getLogById(logId);
        if (log != null) {
            log.setPlayedFiles(toJsonArray(playedFiles));
            logDao.update(log);
        }
    }

    /**
     * 异步更新播放文件列表
     */
    public void updatePlayedFilesAsync(long logId, List<String> playedFiles) {
        executor.execute(() -> updatePlayedFiles(logId, playedFiles));
    }

    /**
     * 获取任务的日志列表（LiveData）
     */
    public LiveData<List<TaskLogEntity>> getLogsByTaskId(long taskId) {
        return logDao.getLogsByTaskId(taskId);
    }

    /**
     * 获取任务的日志列表（同步）
     */
    public List<TaskLogEntity> getLogsByTaskIdSync(long taskId) {
        return logDao.getLogsByTaskIdSync(taskId);
    }

    /**
     * 获取单条日志
     */
    public TaskLogEntity getLogById(long logId) {
        return logDao.getLogById(logId);
    }

    /**
     * 获取任务最新的进行中日志
     */
    public TaskLogEntity getInProgressLog(long taskId) {
        return logDao.getInProgressLog(taskId);
    }

    /**
     * 清理30天前的日志
     * @return 删除的记录数
     */
    public int cleanOldLogs() {
        long thirtyDaysAgo = System.currentTimeMillis() - THIRTY_DAYS_MILLIS;
        int deletedCount = logDao.deleteLogsOlderThan(thirtyDaysAgo);
        Log.d(TAG, "Cleaned " + deletedCount + " old logs");
        return deletedCount;
    }

    /**
     * 异步清理过期日志
     */
    public void cleanOldLogsAsync(CleanCallback callback) {
        executor.execute(() -> {
            int deletedCount = cleanOldLogs();
            if (callback != null) {
                callback.onCleanCompleted(deletedCount);
            }
        });
    }

    /**
     * 获取任务的日志数量
     */
    public int getLogCountByTaskId(long taskId) {
        return logDao.getLogCountByTaskId(taskId);
    }

    /**
     * 获取所有日志数量
     */
    public int getTotalLogCount() {
        return logDao.getTotalLogCount();
    }

    /**
     * 删除任务的所有日志
     */
    public void deleteLogsByTaskId(long taskId) {
        logDao.deleteLogsByTaskId(taskId);
    }

    /**
     * 异步删除任务日志
     */
    public void deleteLogsByTaskIdAsync(long taskId) {
        executor.execute(() -> deleteLogsByTaskId(taskId));
    }

    /**
     * 获取最近的日志
     */
    public List<TaskLogEntity> getRecentLogs(int limit) {
        return logDao.getRecentLogs(limit);
    }

    /**
     * 获取指定状态的日志数量
     */
    public int getSuccessLogCount(long taskId) {
        return logDao.getLogCountByStatus(taskId, LogStatus.SUCCESS);
    }

    public int getFailedLogCount(long taskId) {
        return logDao.getLogCountByStatus(taskId, LogStatus.FAILED);
    }

    /**
     * 将文件列表转换为JSON数组字符串
     */
    private String toJsonArray(List<String> files) {
        if (files == null || files.isEmpty()) {
            return "[]";
        }
        JSONArray jsonArray = new JSONArray();
        for (String file : files) {
            jsonArray.put(file);
        }
        return jsonArray.toString();
    }

    /**
     * 日志创建回调
     */
    public interface LogCallback {
        void onLogCreated(long logId);
    }

    /**
     * 清理完成回调
     */
    public interface CleanCallback {
        void onCleanCompleted(int deletedCount);
    }
}
