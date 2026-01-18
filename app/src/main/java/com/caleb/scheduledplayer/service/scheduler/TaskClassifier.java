package com.caleb.scheduledplayer.service.scheduler;

import com.caleb.scheduledplayer.util.AppLogger;

import com.caleb.scheduledplayer.data.entity.TaskEntity;

/**
 * 任务分类器
 * 根据任务属性识别任务类型
 */
public class TaskClassifier {

    private static final String TAG = "TaskClassifier";

    /**
     * 对任务进行分类
     * @param task 任务实体
     * @return 任务类型
     */
    public static TaskType classify(TaskEntity task) {
        if (task == null) {
            throw new IllegalArgumentException("Task cannot be null");
        }

        boolean isOneTime = task.getRepeatDays() == 0;
        boolean isEveryday = task.getRepeatDays() == TaskEntity.EVERYDAY;
        boolean isAllDay = task.isAllDayPlay();
        boolean isCrossDay = isCrossDayTask(task);

        // 全天播放模式
        if (isAllDay) {
            if (isOneTime) {
                return TaskType.ONE_TIME_ALL_DAY;
            }
            // 每天全天和重复全天统一处理为重复全天
            return TaskType.REPEAT_ALL_DAY;
        }

        // 一次性任务
        if (isOneTime) {
            return isCrossDay ? TaskType.ONE_TIME_CROSS_DAY : TaskType.ONE_TIME_NORMAL;
        }

        // 每天重复任务
        if (isEveryday) {
            return isCrossDay ? TaskType.EVERYDAY_CROSS_DAY : TaskType.EVERYDAY_NORMAL;
        }

        // 普通重复任务
        return isCrossDay ? TaskType.REPEAT_CROSS_DAY : TaskType.REPEAT_NORMAL;
    }

    /**
     * 判断任务是否为跨天任务
     * 跨天定义：结束时间小于开始时间（如 22:00-02:00）
     * 
     * @param task 任务实体
     * @return 是否跨天
     */
    public static boolean isCrossDayTask(TaskEntity task) {
        if (task == null) {
            return false;
        }

        // 全天播放模式不存在跨天概念
        if (task.isAllDayPlay()) {
            return false;
        }

        int startMinutes = parseTimeToMinutes(task.getStartTime());
        int endMinutes = parseTimeToMinutes(task.getEndTime());

        // 如果解析失败，视为非跨天
        if (startMinutes < 0 || endMinutes < 0) {
            AppLogger.w(TAG, "isCrossDayTask: failed to parse time for task " + task.getId());
            return false;
        }

        // 结束时间小于开始时间表示跨天
        // 注意：相等时不算跨天（虽然这种情况逻辑上有问题）
        return endMinutes < startMinutes;
    }

    /**
     * 将时间字符串解析为分钟数
     * @param time 时间字符串，格式 "HH:mm"
     * @return 从午夜开始的分钟数，解析失败返回 -1
     */
    public static int parseTimeToMinutes(String time) {
        if (time == null || time.isEmpty()) {
            AppLogger.w(TAG, "parseTimeToMinutes: time is null or empty");
            return -1;
        }

        try {
            String[] parts = time.split(":");
            if (parts.length >= 2) {
                int hours = Integer.parseInt(parts[0].trim());
                int minutes = Integer.parseInt(parts[1].trim());
                
                // 验证时间范围
                if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59) {
                    AppLogger.w(TAG, "parseTimeToMinutes: invalid time value - " + time);
                    return -1;
                }
                
                return hours * 60 + minutes;
            } else {
                AppLogger.w(TAG, "parseTimeToMinutes: invalid format - " + time);
            }
        } catch (NumberFormatException e) {
            AppLogger.w(TAG, "parseTimeToMinutes: failed to parse - " + time + ", error: " + e.getMessage());
        }

        return -1;
    }

    /**
     * 将分钟数转换为时间字符串
     * @param minutes 从午夜开始的分钟数
     * @return 时间字符串，格式 "HH:mm"
     */
    public static String minutesToTimeString(int minutes) {
        int hours = (minutes / 60) % 24;
        int mins = minutes % 60;
        return String.format("%02d:%02d", hours, mins);
    }

    /**
     * 获取任务的描述信息
     * @param task 任务实体
     * @return 描述字符串
     */
    public static String getTaskDescription(TaskEntity task) {
        if (task == null) {
            return "未知任务";
        }

        TaskType type = classify(task);
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(type.getDescription()).append("] ");
        sb.append(task.getName());

        if (!task.isAllDayPlay()) {
            sb.append(" (").append(task.getStartTime())
              .append("-").append(task.getEndTime()).append(")");
        }

        return sb.toString();
    }
}
