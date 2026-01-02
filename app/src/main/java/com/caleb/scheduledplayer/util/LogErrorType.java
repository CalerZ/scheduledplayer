package com.caleb.scheduledplayer.util;

/**
 * 任务执行日志错误类型常量
 */
public final class LogErrorType {

    private LogErrorType() {
        // 私有构造函数，防止实例化
    }

    /**
     * 文件缺失
     */
    public static final int FILE_MISSING = 1;

    /**
     * 权限问题
     */
    public static final int PERMISSION_DENIED = 2;

    /**
     * 播放器错误
     */
    public static final int PLAYER_ERROR = 3;

    /**
     * 其他异常
     */
    public static final int OTHER = 4;

    /**
     * 蓝牙未连接
     */
    public static final int BLUETOOTH_NOT_CONNECTED = 5;

    /**
     * 蓝牙断开连接
     */
    public static final int BLUETOOTH_DISCONNECTED = 6;

    /**
     * 获取错误类型的显示文本
     */
    public static String getDisplayText(Integer errorType) {
        if (errorType == null) {
            return "";
        }
        switch (errorType) {
            case FILE_MISSING:
                return "文件缺失";
            case PERMISSION_DENIED:
                return "权限问题";
            case PLAYER_ERROR:
                return "播放器错误";
            case OTHER:
                return "其他异常";
            case BLUETOOTH_NOT_CONNECTED:
                return "蓝牙未连接";
            case BLUETOOTH_DISCONNECTED:
                return "蓝牙断开";
            default:
                return "未知错误";
        }
    }
}
