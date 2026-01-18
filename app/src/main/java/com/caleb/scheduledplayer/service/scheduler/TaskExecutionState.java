package com.caleb.scheduledplayer.service.scheduler;

/**
 * 任务执行状态枚举
 * 用于追踪任务在生命周期中的当前阶段
 */
public enum TaskExecutionState {
    /**
     * 空闲状态：任务未调度或已完成上一次执行
     */
    IDLE(0, "空闲"),

    /**
     * 已调度状态：闹钟已设置，等待触发
     */
    SCHEDULED(1, "已调度"),

    /**
     * 执行中状态：正在播放
     */
    EXECUTING(2, "执行中"),

    /**
     * 暂停状态：因蓝牙断开等原因暂停
     */
    PAUSED(3, "已暂停"),

    /**
     * 已完成状态：本次执行周期结束
     */
    COMPLETED(4, "已完成"),

    /**
     * 已禁用状态：任务被用户或系统禁用
     */
    DISABLED(5, "已禁用"),

    /**
     * 已跳过状态：因并发限制等待超时，本次执行被跳过
     */
    SKIPPED(6, "已跳过"),

    /**
     * 等待空位状态：因并发播放数达上限，等待空位
     */
    WAITING_SLOT(7, "等待空位");

    private final int value;
    private final String description;

    TaskExecutionState(int value, String description) {
        this.value = value;
        this.description = description;
    }

    public int getValue() {
        return value;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 根据整数值获取对应的状态
     * @param value 状态值
     * @return 对应的状态枚举，如果值无效则返回 IDLE
     */
    public static TaskExecutionState fromValue(int value) {
        for (TaskExecutionState state : values()) {
            if (state.value == value) {
                return state;
            }
        }
        return IDLE;
    }

    /**
     * 检查是否可以从当前状态转换到目标状态
     * @param targetState 目标状态
     * @return 是否允许转换
     */
    public boolean canTransitionTo(TaskExecutionState targetState) {
        switch (this) {
            case IDLE:
                // 空闲状态可以转换到：已调度、已禁用
                return targetState == SCHEDULED || targetState == DISABLED;

            case SCHEDULED:
                // 已调度状态可以转换到：执行中、等待空位、空闲（取消调度）、已禁用
                return targetState == EXECUTING || targetState == WAITING_SLOT 
                        || targetState == IDLE || targetState == DISABLED;

            case EXECUTING:
                // 执行中状态可以转换到：已完成、暂停、已禁用
                return targetState == COMPLETED || targetState == PAUSED || targetState == DISABLED;

            case PAUSED:
                // 暂停状态可以转换到：执行中（恢复）、已完成（时间到）、已禁用
                return targetState == EXECUTING || targetState == COMPLETED || targetState == DISABLED;

            case COMPLETED:
                // 已完成状态可以转换到：已调度（重复任务）、已禁用（一次性任务）、空闲
                return targetState == SCHEDULED || targetState == DISABLED || targetState == IDLE;

            case DISABLED:
                // 已禁用状态可以转换到：空闲（重新启用）
                return targetState == IDLE;

            case SKIPPED:
                // 已跳过状态可以转换到：已调度（重复任务下一次）、执行中（下次闹钟触发）、已禁用（一次性任务）、空闲
                return targetState == SCHEDULED || targetState == EXECUTING 
                        || targetState == WAITING_SLOT || targetState == DISABLED || targetState == IDLE;

            case WAITING_SLOT:
                // 等待空位状态可以转换到：执行中（获得空位）、已跳过（等待超时）、已禁用、空闲（用户手动停止）
                return targetState == EXECUTING || targetState == SKIPPED 
                        || targetState == DISABLED || targetState == IDLE;

            default:
                return false;
        }
    }

    /**
     * 检查当前状态是否表示任务处于活跃状态（正在播放或暂停）
     * @return 是否活跃
     */
    public boolean isActive() {
        return this == EXECUTING || this == PAUSED;
    }

    /**
     * 检查当前状态是否表示任务正在等待执行（包括等待空位）
     * @return 是否等待中
     */
    public boolean isWaiting() {
        return this == WAITING_SLOT;
    }

    /**
     * 检查当前状态是否允许开始播放
     * @return 是否可以开始播放
     */
    public boolean canStartPlayback() {
        return this == IDLE || this == SCHEDULED || this == PAUSED || this == WAITING_SLOT;
    }

    /**
     * 检查当前状态是否允许调度
     * @return 是否可以调度
     */
    public boolean canSchedule() {
        return this == IDLE || this == COMPLETED;
    }
}
