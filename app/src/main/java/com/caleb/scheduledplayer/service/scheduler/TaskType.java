package com.caleb.scheduledplayer.service.scheduler;

/**
 * 任务类型枚举
 * 根据重复类型、时间类型、播放模式三个维度划分
 */
public enum TaskType {
    /**
     * 一次性非跨天定时段任务
     * 例如：今天 9:00-12:00 播放一次
     */
    ONE_TIME_NORMAL("一次性定时段"),

    /**
     * 一次性跨天定时段任务
     * 例如：今晚 22:00 到明天 02:00 播放一次
     */
    ONE_TIME_CROSS_DAY("一次性跨天"),

    /**
     * 一次性全天播放任务
     * 例如：今天全天播放一次
     */
    ONE_TIME_ALL_DAY("一次性全天"),

    /**
     * 重复非跨天定时段任务
     * 例如：每周一三五 9:00-12:00
     */
    REPEAT_NORMAL("重复定时段"),

    /**
     * 重复跨天定时段任务
     * 例如：每周五六 22:00-02:00
     */
    REPEAT_CROSS_DAY("重复跨天"),

    /**
     * 重复全天播放任务
     * 例如：每周一到五全天播放
     */
    REPEAT_ALL_DAY("重复全天"),

    /**
     * 每天非跨天定时段任务
     * 例如：每天 8:00-18:00
     */
    EVERYDAY_NORMAL("每天定时段"),

    /**
     * 每天跨天定时段任务
     * 例如：每天 22:00-06:00
     */
    EVERYDAY_CROSS_DAY("每天跨天");

    private final String description;

    TaskType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 是否为一次性任务
     */
    public boolean isOneTime() {
        return this == ONE_TIME_NORMAL || this == ONE_TIME_CROSS_DAY || this == ONE_TIME_ALL_DAY;
    }

    /**
     * 是否为跨天任务
     */
    public boolean isCrossDay() {
        return this == ONE_TIME_CROSS_DAY || this == REPEAT_CROSS_DAY || this == EVERYDAY_CROSS_DAY;
    }

    /**
     * 是否为全天播放任务
     */
    public boolean isAllDay() {
        return this == ONE_TIME_ALL_DAY || this == REPEAT_ALL_DAY;
    }

    /**
     * 是否为重复任务（包括每天）
     */
    public boolean isRepeat() {
        return !isOneTime();
    }

    /**
     * 是否为每天重复任务
     */
    public boolean isEveryday() {
        return this == EVERYDAY_NORMAL || this == EVERYDAY_CROSS_DAY;
    }
}
