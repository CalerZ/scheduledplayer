package com.caleb.scheduledplayer.util;

/**
 * 任务执行日志状态常量
 */
public final class LogStatus {
    
    private LogStatus() {
        // 私有构造函数，防止实例化
    }

    /**
     * 进行中
     */
    public static final int IN_PROGRESS = 0;

    /**
     * 执行成功
     */
    public static final int SUCCESS = 1;

    /**
     * 执行失败
     */
    public static final int FAILED = 2;

    /**
     * 获取状态的显示文本
     */
    public static String getDisplayText(int status) {
        switch (status) {
            case IN_PROGRESS:
                return "进行中";
            case SUCCESS:
                return "成功";
            case FAILED:
                return "失败";
            default:
                return "未知";
        }
    }
}
