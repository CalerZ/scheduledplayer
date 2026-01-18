package com.caleb.scheduledplayer.service.scheduler;

/**
 * 时间检查结果
 * 封装 shouldBeActiveNow 方法的返回值，包含是否活跃、原因和有效结束时间
 */
public class TimeCheckResult {

    /**
     * 活跃/非活跃原因枚举
     */
    public enum ActiveReason {
        // ===== 活跃原因 =====
        
        /**
         * 在正常时间范围内（非跨天任务）
         */
        IN_NORMAL_RANGE("在时间范围内"),

        /**
         * 在跨天任务的晚间部分（开始时间之后）
         */
        IN_CROSS_DAY_EVENING("在跨天任务晚间部分"),

        /**
         * 在跨天任务的凌晨部分（结束时间之前）
         */
        IN_CROSS_DAY_MORNING("在跨天任务凌晨部分"),

        /**
         * 全天播放且今天有效
         */
        ALL_DAY_ACTIVE("全天播放中"),

        // ===== 非活跃原因 =====

        /**
         * 不在时间范围内
         */
        NOT_IN_RANGE("不在时间范围内"),

        /**
         * 今天/昨天不在重复日中
         */
        NOT_REPEAT_DAY("不在重复日中"),

        /**
         * 一次性跨天任务凌晨部分，无执行状态记录
         * 保守处理，避免重启后重复执行
         */
        ONE_TIME_MORNING_NO_STATE("一次性跨天凌晨无执行状态"),

        /**
         * 任务已禁用
         */
        TASK_DISABLED("任务已禁用"),

        /**
         * 一次性任务已完成
         */
        TASK_COMPLETED("任务已完成"),

        /**
         * 任务实体为空
         */
        TASK_NULL("任务为空"),

        /**
         * 全天播放但今天不在重复日中
         */
        ALL_DAY_NOT_TODAY("全天播放但今天不执行");

        private final String description;

        ActiveReason(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }

        /**
         * 判断此原因是否表示活跃状态
         */
        public boolean isActiveReason() {
            return this == IN_NORMAL_RANGE 
                || this == IN_CROSS_DAY_EVENING 
                || this == IN_CROSS_DAY_MORNING 
                || this == ALL_DAY_ACTIVE;
        }
    }

    private final boolean active;
    private final ActiveReason reason;
    private final long effectiveEndTime;

    /**
     * 私有构造函数
     */
    private TimeCheckResult(boolean active, ActiveReason reason, long effectiveEndTime) {
        this.active = active;
        this.reason = reason;
        this.effectiveEndTime = effectiveEndTime;
    }

    // ===== 静态工厂方法 =====

    /**
     * 创建活跃状态的结果
     * @param reason 活跃原因
     * @param effectiveEndTime 有效的结束时间戳
     * @return TimeCheckResult
     */
    public static TimeCheckResult active(ActiveReason reason, long effectiveEndTime) {
        if (!reason.isActiveReason()) {
            throw new IllegalArgumentException("Reason " + reason + " is not an active reason");
        }
        return new TimeCheckResult(true, reason, effectiveEndTime);
    }

    /**
     * 创建非活跃状态的结果
     * @param reason 非活跃原因
     * @return TimeCheckResult
     */
    public static TimeCheckResult inactive(ActiveReason reason) {
        if (reason.isActiveReason()) {
            throw new IllegalArgumentException("Reason " + reason + " is not an inactive reason");
        }
        return new TimeCheckResult(false, reason, 0);
    }

    // ===== Getters =====

    /**
     * 是否处于活跃状态
     */
    public boolean isActive() {
        return active;
    }

    /**
     * 获取原因
     */
    public ActiveReason getReason() {
        return reason;
    }

    /**
     * 获取有效的结束时间
     * 仅当 active=true 时有意义
     */
    public long getEffectiveEndTime() {
        return effectiveEndTime;
    }

    @Override
    public String toString() {
        if (active) {
            return "TimeCheckResult{active=true, reason=" + reason 
                + ", effectiveEndTime=" + new java.util.Date(effectiveEndTime) + "}";
        } else {
            return "TimeCheckResult{active=false, reason=" + reason + "}";
        }
    }
}
