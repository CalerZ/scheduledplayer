package com.caleb.scheduledplayer.service.scheduler;

import com.caleb.scheduledplayer.data.entity.TaskEntity;

/**
 * 调度策略接口
 * 定义不同任务类型的调度行为
 */
public interface ScheduleStrategy {

    /**
     * 调度任务
     * 根据任务当前状态决定是立即执行还是设置闹钟
     * 
     * @param task 任务实体
     * @param manager 调度管理器（用于执行实际操作）
     * @return ScheduleResult 调度结果
     */
    ScheduleResult schedule(TaskEntity task, TaskScheduleManager manager);

    /**
     * 处理开始闹钟触发
     * 
     * @param task 任务实体
     * @param manager 调度管理器
     */
    void handleStart(TaskEntity task, TaskScheduleManager manager);

    /**
     * 处理结束闹钟触发
     * 
     * @param task 任务实体
     * @param manager 调度管理器
     */
    void handleStop(TaskEntity task, TaskScheduleManager manager);

    /**
     * 处理设备重启后的恢复
     * 
     * @param task 任务实体
     * @param manager 调度管理器
     */
    void handleReboot(TaskEntity task, TaskScheduleManager manager);

    /**
     * 处理重试启动（因并发限制等待后的重试）
     * 
     * @param task 任务实体
     * @param manager 调度管理器
     */
    void handleRetryStart(TaskEntity task, TaskScheduleManager manager);

    /**
     * 获取此策略支持的任务类型
     * 
     * @return 任务类型
     */
    TaskType getSupportedType();
}
