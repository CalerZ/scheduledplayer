package com.caleb.scheduledplayer.service.scheduler;

import java.util.Date;

/**
 * 调度结果
 * 封装 schedule 方法的返回值，包含调度类型和闹钟时间
 */
public class ScheduleResult {

    /**
     * 调度结果类型
     */
    public enum ResultType {
        /**
         * 立即执行：任务当前应该活跃，已启动播放并设置结束闹钟
         */
        IMMEDIATE,

        /**
         * 已调度：设置了开始和结束闹钟，等待触发
         */
        SCHEDULED,

        /**
         * 仅结束闹钟：只设置了结束闹钟（如一次性跨天任务凌晨部分）
         */
        END_ONLY,

        /**
         * 无调度：任务不需要调度（已禁用、已完成或无有效执行时间）
         */
        NO_SCHEDULE
    }

    private final ResultType resultType;
    private final long startTime;
    private final long endTime;
    private final String message;

    /**
     * 私有构造函数
     */
    private ScheduleResult(ResultType resultType, long startTime, long endTime, String message) {
        this.resultType = resultType;
        this.startTime = startTime;
        this.endTime = endTime;
        this.message = message;
    }

    // ===== 静态工厂方法 =====

    /**
     * 创建立即执行的结果
     * @param endTime 结束时间戳
     * @return ScheduleResult
     */
    public static ScheduleResult immediate(long endTime) {
        return new ScheduleResult(ResultType.IMMEDIATE, 0, endTime, 
                "Task started immediately, end at " + new Date(endTime));
    }

    /**
     * 创建已调度的结果
     * @param startTime 开始时间戳
     * @param endTime 结束时间戳
     * @return ScheduleResult
     */
    public static ScheduleResult scheduled(long startTime, long endTime) {
        return new ScheduleResult(ResultType.SCHEDULED, startTime, endTime,
                "Scheduled start at " + new Date(startTime) + ", end at " + new Date(endTime));
    }

    /**
     * 创建仅结束闹钟的结果
     * @param endTime 结束时间戳
     * @return ScheduleResult
     */
    public static ScheduleResult endOnly(long endTime) {
        return new ScheduleResult(ResultType.END_ONLY, 0, endTime,
                "Only end alarm set at " + new Date(endTime));
    }

    /**
     * 创建无调度的结果
     * @param reason 原因说明
     * @return ScheduleResult
     */
    public static ScheduleResult noSchedule(String reason) {
        return new ScheduleResult(ResultType.NO_SCHEDULE, 0, 0, reason);
    }

    /**
     * 创建无调度的结果（默认原因）
     * @return ScheduleResult
     */
    public static ScheduleResult noSchedule() {
        return noSchedule("Task not scheduled");
    }

    // ===== Getters =====

    public ResultType getResultType() {
        return resultType;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public String getMessage() {
        return message;
    }

    /**
     * 是否成功调度（包括立即执行、已调度、仅结束闹钟）
     */
    public boolean isScheduled() {
        return resultType != ResultType.NO_SCHEDULE;
    }

    /**
     * 是否立即开始执行
     */
    public boolean isImmediate() {
        return resultType == ResultType.IMMEDIATE;
    }

    @Override
    public String toString() {
        return "ScheduleResult{" +
                "type=" + resultType +
                ", startTime=" + (startTime > 0 ? new Date(startTime) : "N/A") +
                ", endTime=" + (endTime > 0 ? new Date(endTime) : "N/A") +
                ", message='" + message + '\'' +
                '}';
    }
}
