package com.caleb.scheduledplayer.service.scheduler;

import com.caleb.scheduledplayer.util.AppLogger;

import com.caleb.scheduledplayer.data.entity.TaskEntity;

import java.util.Calendar;

/**
 * 任务时间计算器
 * 统一处理所有时间相关的计算逻辑
 */
public class TaskTimeCalculator {

    private static final String TAG = "TaskTimeCalculator";

    /**
     * 获取有效的时间分钟数
     * 如果解析失败返回默认值
     */
    private static int getValidMinutes(String time, int defaultValue) {
        int minutes = TaskClassifier.parseTimeToMinutes(time);
        return minutes >= 0 ? minutes : defaultValue;
    }

    /**
     * 判断任务当前是否应该处于活跃状态
     * @param task 任务实体
     * @return TimeCheckResult 包含是否活跃及原因
     */
    public static TimeCheckResult shouldBeActiveNow(TaskEntity task) {
        if (task == null) {
            return TimeCheckResult.inactive(TimeCheckResult.ActiveReason.TASK_NULL);
        }

        if (!task.isEnabled()) {
            return TimeCheckResult.inactive(TimeCheckResult.ActiveReason.TASK_DISABLED);
        }

        TaskType type = TaskClassifier.classify(task);
        Calendar now = Calendar.getInstance();

        switch (type) {
            case ONE_TIME_ALL_DAY:
            case REPEAT_ALL_DAY:
                return checkAllDayActive(task, now);

            case ONE_TIME_NORMAL:
            case REPEAT_NORMAL:
            case EVERYDAY_NORMAL:
                return checkNormalTimeRange(task, now);

            case ONE_TIME_CROSS_DAY:
            case REPEAT_CROSS_DAY:
            case EVERYDAY_CROSS_DAY:
                return checkCrossDayTimeRange(task, now);

            default:
                return TimeCheckResult.inactive(TimeCheckResult.ActiveReason.NOT_IN_RANGE);
        }
    }

    /**
     * 检查全天播放任务是否活跃
     */
    private static TimeCheckResult checkAllDayActive(TaskEntity task, Calendar now) {
        boolean todayValid = shouldExecuteOnDay(task.getRepeatDays(), now);

        if (todayValid) {
            // 全天任务的结束时间是今天午夜（明天 00:00:05）
            long endTime = getMidnightCheckTime(now);
            return TimeCheckResult.active(TimeCheckResult.ActiveReason.ALL_DAY_ACTIVE, endTime);
        }

        return TimeCheckResult.inactive(TimeCheckResult.ActiveReason.ALL_DAY_NOT_TODAY);
    }

    /**
     * 检查非跨天任务是否在时间范围内
     */
    private static TimeCheckResult checkNormalTimeRange(TaskEntity task, Calendar now) {
        int currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int startMinutes = getValidMinutes(task.getStartTime(), 0);
        int endMinutes = getValidMinutes(task.getEndTime(), 0);

        // 检查时间范围
        if (currentMinutes >= startMinutes && currentMinutes < endMinutes) {
            // 检查今天是否在重复日中
            if (shouldExecuteOnDay(task.getRepeatDays(), now)) {
                long endTime = calculateEndTimeToday(now, endMinutes);
                return TimeCheckResult.active(TimeCheckResult.ActiveReason.IN_NORMAL_RANGE, endTime);
            }
            return TimeCheckResult.inactive(TimeCheckResult.ActiveReason.NOT_REPEAT_DAY);
        }

        return TimeCheckResult.inactive(TimeCheckResult.ActiveReason.NOT_IN_RANGE);
    }

    /**
     * 检查跨天任务是否在时间范围内
     */
    private static TimeCheckResult checkCrossDayTimeRange(TaskEntity task, Calendar now) {
        int currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int startMinutes = getValidMinutes(task.getStartTime(), 0);
        int endMinutes = getValidMinutes(task.getEndTime(), 0);

        TaskType type = TaskClassifier.classify(task);

        if (currentMinutes >= startMinutes) {
            // 晚间部分：当前时间在开始时间之后
            // 检查"今天"是否在重复日中
            if (shouldExecuteOnDay(task.getRepeatDays(), now)) {
                // 结束时间是明天的结束时间
                long endTime = calculateEndTimeTomorrow(now, endMinutes);
                return TimeCheckResult.active(TimeCheckResult.ActiveReason.IN_CROSS_DAY_EVENING, endTime);
            }
            return TimeCheckResult.inactive(TimeCheckResult.ActiveReason.NOT_REPEAT_DAY);

        } else if (currentMinutes < endMinutes) {
            // 凌晨部分：当前时间在结束时间之前
            // 需要检查"昨天"是否在重复日中

            // 一次性跨天任务的特殊处理
            if (type == TaskType.ONE_TIME_CROSS_DAY) {
                // 检查任务的执行状态
                TaskExecutionState state = task.getExecutionStateEnum();
                if (state == TaskExecutionState.EXECUTING || state == TaskExecutionState.PAUSED) {
                    // 有执行状态记录，可以恢复
                    long endTime = calculateEndTimeToday(now, endMinutes);
                    return TimeCheckResult.active(TimeCheckResult.ActiveReason.IN_CROSS_DAY_MORNING, endTime);
                }
                // 无执行状态，保守处理不恢复
                return TimeCheckResult.inactive(TimeCheckResult.ActiveReason.ONE_TIME_MORNING_NO_STATE);
            }

            // 重复任务：检查昨天是否在重复日中
            Calendar yesterday = (Calendar) now.clone();
            yesterday.add(Calendar.DAY_OF_YEAR, -1);

            if (shouldExecuteOnDay(task.getRepeatDays(), yesterday)) {
                long endTime = calculateEndTimeToday(now, endMinutes);
                return TimeCheckResult.active(TimeCheckResult.ActiveReason.IN_CROSS_DAY_MORNING, endTime);
            }
            return TimeCheckResult.inactive(TimeCheckResult.ActiveReason.NOT_REPEAT_DAY);

        } else {
            // 白天部分：不在跨天任务的时间范围内
            return TimeCheckResult.inactive(TimeCheckResult.ActiveReason.NOT_IN_RANGE);
        }
    }

    /**
     * 计算下一次开始时间
     * @param task 任务实体
     * @return 下一次开始的时间戳，-1 表示无下次执行
     */
    public static long calculateNextStartTime(TaskEntity task) {
        if (task == null || !task.isEnabled()) {
            return -1;
        }

        TaskType type = TaskClassifier.classify(task);
        Calendar now = Calendar.getInstance();
        int startMinutes = getValidMinutes(task.getStartTime(), 0);

        // 一次性任务
        if (type.isOneTime()) {
            Calendar startCal = getCalendarForTime(now, startMinutes);

            // 如果开始时间已过
            if (startCal.getTimeInMillis() <= now.getTimeInMillis()) {
                // 对于一次性任务，如果今天的时间已过，检查是否在执行中
                TaskExecutionState state = task.getExecutionStateEnum();
                if (state == TaskExecutionState.EXECUTING || state == TaskExecutionState.PAUSED) {
                    // 正在执行中，不需要再调度开始
                    return -1;
                }
                // 时间已过且未执行，对于一次性任务没有下次
                return -1;
            }

            return startCal.getTimeInMillis();
        }

        // 重复任务：找到下一个有效的执行日
        Calendar startCal = getCalendarForTime(now, startMinutes);

        // 如果今天的开始时间已过，从明天开始找
        if (startCal.getTimeInMillis() <= now.getTimeInMillis()) {
            startCal.add(Calendar.DAY_OF_YEAR, 1);
        }

        // 最多查找7天
        for (int i = 0; i < 7; i++) {
            if (shouldExecuteOnDay(task.getRepeatDays(), startCal)) {
                return startCal.getTimeInMillis();
            }
            startCal.add(Calendar.DAY_OF_YEAR, 1);
        }

        // 没有找到有效的执行日（理论上不应该发生）
        AppLogger.w(TAG, "No valid execution day found for task " + task.getId());
        return -1;
    }

    /**
     * 计算当前执行周期的结束时间
     * 假设任务当前正在执行或即将执行
     * @param task 任务实体
     * @return 结束时间戳
     */
    public static long calculateCurrentEndTime(TaskEntity task) {
        if (task == null) {
            return -1;
        }

        TaskType type = TaskClassifier.classify(task);
        Calendar now = Calendar.getInstance();

        // 全天播放任务
        if (type.isAllDay()) {
            return getMidnightCheckTime(now);
        }

        int startMinutes = getValidMinutes(task.getStartTime(), 0);
        int endMinutes = getValidMinutes(task.getEndTime(), 0);
        int currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);

        if (type.isCrossDay()) {
            // 跨天任务
            if (currentMinutes >= startMinutes) {
                // 晚间部分，结束时间是明天
                return calculateEndTimeTomorrow(now, endMinutes);
            } else {
                // 凌晨部分，结束时间是今天
                return calculateEndTimeToday(now, endMinutes);
            }
        } else {
            // 非跨天任务，结束时间是今天
            return calculateEndTimeToday(now, endMinutes);
        }
    }

    /**
     * 根据开始时间计算对应的结束时间
     * @param task 任务实体
     * @param startTime 开始时间戳
     * @return 结束时间戳
     */
    public static long calculateEndTimeForStart(TaskEntity task, long startTime) {
        if (task == null) {
            return -1;
        }

        TaskType type = TaskClassifier.classify(task);

        // 全天播放任务
        if (type.isAllDay()) {
            Calendar startCal = Calendar.getInstance();
            startCal.setTimeInMillis(startTime);
            return getMidnightCheckTime(startCal);
        }

        int endMinutes = getValidMinutes(task.getEndTime(), 0);
        Calendar startCal = Calendar.getInstance();
        startCal.setTimeInMillis(startTime);

        Calendar endCal = (Calendar) startCal.clone();
        endCal.set(Calendar.HOUR_OF_DAY, endMinutes / 60);
        endCal.set(Calendar.MINUTE, endMinutes % 60);
        endCal.set(Calendar.SECOND, 0);
        endCal.set(Calendar.MILLISECOND, 0);

        // 如果是跨天任务，结束时间加一天
        if (type.isCrossDay()) {
            endCal.add(Calendar.DAY_OF_YEAR, 1);
        }

        return endCal.getTimeInMillis();
    }

    /**
     * 判断指定日期是否在重复日中
     * @param repeatDays 重复日位掩码
     * @param calendar 要检查的日期
     * @return 是否在重复日中
     */
    public static boolean shouldExecuteOnDay(int repeatDays, Calendar calendar) {
        // 一次性任务（repeatDays=0）总是返回 true
        if (repeatDays == 0) {
            return true;
        }

        int dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK);
        int dayFlag = getDayFlag(dayOfWeek);

        return (repeatDays & dayFlag) != 0;
    }

    /**
     * 将 Calendar.DAY_OF_WEEK 转换为 TaskEntity 的 dayFlag
     */
    public static int getDayFlag(int calendarDayOfWeek) {
        switch (calendarDayOfWeek) {
            case Calendar.MONDAY:
                return TaskEntity.MONDAY;
            case Calendar.TUESDAY:
                return TaskEntity.TUESDAY;
            case Calendar.WEDNESDAY:
                return TaskEntity.WEDNESDAY;
            case Calendar.THURSDAY:
                return TaskEntity.THURSDAY;
            case Calendar.FRIDAY:
                return TaskEntity.FRIDAY;
            case Calendar.SATURDAY:
                return TaskEntity.SATURDAY;
            case Calendar.SUNDAY:
                return TaskEntity.SUNDAY;
            default:
                return 0;
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 获取今天指定时间的 Calendar
     */
    private static Calendar getCalendarForTime(Calendar base, int minutes) {
        Calendar cal = (Calendar) base.clone();
        cal.set(Calendar.HOUR_OF_DAY, minutes / 60);
        cal.set(Calendar.MINUTE, minutes % 60);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal;
    }

    /**
     * 计算今天的结束时间
     */
    private static long calculateEndTimeToday(Calendar now, int endMinutes) {
        Calendar endCal = (Calendar) now.clone();
        endCal.set(Calendar.HOUR_OF_DAY, endMinutes / 60);
        endCal.set(Calendar.MINUTE, endMinutes % 60);
        endCal.set(Calendar.SECOND, 0);
        endCal.set(Calendar.MILLISECOND, 0);
        return endCal.getTimeInMillis();
    }

    /**
     * 计算明天的结束时间
     */
    private static long calculateEndTimeTomorrow(Calendar now, int endMinutes) {
        Calendar endCal = (Calendar) now.clone();
        endCal.add(Calendar.DAY_OF_YEAR, 1);
        endCal.set(Calendar.HOUR_OF_DAY, endMinutes / 60);
        endCal.set(Calendar.MINUTE, endMinutes % 60);
        endCal.set(Calendar.SECOND, 0);
        endCal.set(Calendar.MILLISECOND, 0);
        return endCal.getTimeInMillis();
    }

    /**
     * 获取午夜检查时间（明天 00:00:05）
     * 使用 00:00:05 而不是 00:00:00 避免边界问题
     */
    private static long getMidnightCheckTime(Calendar base) {
        Calendar midnight = (Calendar) base.clone();
        midnight.add(Calendar.DAY_OF_YEAR, 1);
        midnight.set(Calendar.HOUR_OF_DAY, 0);
        midnight.set(Calendar.MINUTE, 0);
        midnight.set(Calendar.SECOND, 5);
        midnight.set(Calendar.MILLISECOND, 0);
        return midnight.getTimeInMillis();
    }

    /**
     * 获取今天的午夜检查时间
     * 如果当前已过午夜，则返回明天的
     */
    public static long getNextMidnightCheckTime() {
        Calendar now = Calendar.getInstance();
        return getMidnightCheckTime(now);
    }
}
