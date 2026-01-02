package com.caleb.scheduledplayer.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;

import com.caleb.scheduledplayer.R;

/**
 * 权限辅助工具类
 * 处理各种系统权限和厂商特有权限
 */
public class PermissionHelper {

    private static final String PREFS_NAME = "permission_prefs";
    private static final String KEY_HUAWEI_GUIDE_SHOWN = "huawei_guide_shown";
    private static final String KEY_BATTERY_GUIDE_SHOWN = "battery_guide_shown";
    private static final String KEY_PERMISSION_GUIDE_SHOWN = "permission_guide_shown";

    /**
     * 检查是否已显示过权限引导
     */
    public static boolean hasShownPermissionGuide(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_PERMISSION_GUIDE_SHOWN, false);
    }

    /**
     * 标记权限引导已显示
     */
    public static void setPermissionGuideShown(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_PERMISSION_GUIDE_SHOWN, true).apply();
    }

    /**
     * 检查是否需要显示华为权限引导
     */
    public static boolean shouldShowHuaweiGuide(Context context) {
        if (!HuaweiDeviceHelper.isHuaweiDevice()) {
            return false;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return !prefs.getBoolean(KEY_HUAWEI_GUIDE_SHOWN, false);
    }

    /**
     * 标记华为权限引导已显示
     */
    public static void markHuaweiGuideShown(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_HUAWEI_GUIDE_SHOWN, true).apply();
    }

    /**
     * 检查是否需要显示电池优化引导
     */
    public static boolean shouldShowBatteryGuide(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return !prefs.getBoolean(KEY_BATTERY_GUIDE_SHOWN, false) && !isIgnoringBatteryOptimizations(context);
    }

    /**
     * 标记电池优化引导已显示
     */
    public static void markBatteryGuideShown(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_BATTERY_GUIDE_SHOWN, true).apply();
    }

    /**
     * 检查应用是否已忽略电池优化
     */
    public static boolean isIgnoringBatteryOptimizations(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            return pm.isIgnoringBatteryOptimizations(context.getPackageName());
        }
        return false;
    }

    /**
     * 请求忽略电池优化
     */
    public static void requestIgnoreBatteryOptimizations(Activity activity) {
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + activity.getPackageName()));
        activity.startActivity(intent);
    }

    /**
     * 请求电池优化豁免（别名方法）
     */
    public static void requestBatteryOptimizationExemption(Activity activity) {
        requestIgnoreBatteryOptimizations(activity);
    }

    /**
     * 打开应用详情设置页面
     */
    public static void openAppSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /**
     * 检查精确闹钟权限 (Android 12+)
     */
    @RequiresApi(api = Build.VERSION_CODES.S)
    public static boolean canScheduleExactAlarms(Context context) {
        android.app.AlarmManager alarmManager = 
                (android.app.AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return alarmManager != null && alarmManager.canScheduleExactAlarms();
    }

    /**
     * 打开精确闹钟权限设置页面 (Android 12+)
     */
    @RequiresApi(api = Build.VERSION_CODES.S)
    public static void openExactAlarmSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /**
     * 显示权限引导对话框（通用）
     */
    public static void showPermissionGuideDialog(Activity activity, Runnable onDismiss) {
        String message;
        if (HuaweiDeviceHelper.isHuaweiDevice()) {
            message = "为确保定时任务正常执行，请进行以下设置：\n\n" +
                    "1. 开启自启动权限\n" +
                    "2. 关闭电池优化\n" +
                    "3. 允许后台运行\n\n" +
                    "点击\"去设置\"将打开手机管家，请在其中找到本应用并开启相关权限。";
        } else {
            message = "为确保定时任务正常执行，建议：\n\n" +
                    "1. 关闭电池优化\n" +
                    "2. 允许后台运行";
        }

        new AlertDialog.Builder(activity)
                .setTitle("权限设置")
                .setMessage(message)
                .setPositiveButton("去设置", (dialog, which) -> {
                    if (HuaweiDeviceHelper.isHuaweiDevice()) {
                        // 先尝试打开自启动设置
                        if (!HuaweiDeviceHelper.openAutoStartSettings(activity)) {
                            // 如果失败，打开手机管家
                            HuaweiDeviceHelper.openSystemManager(activity);
                        }
                    } else {
                        // 非华为设备，打开电池优化设置
                        requestIgnoreBatteryOptimizations(activity);
                    }
                    if (onDismiss != null) {
                        onDismiss.run();
                    }
                })
                .setNegativeButton("稍后设置", (dialog, which) -> {
                    if (onDismiss != null) {
                        onDismiss.run();
                    }
                })
                .setCancelable(false)
                .show();
    }

    /**
     * 显示华为权限引导对话框
     */
    public static void showHuaweiPermissionGuideDialog(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.huawei_permission_guide_title)
                .setMessage("为确保定时任务正常执行，请进行以下设置：\n\n" +
                        "1. 开启自启动权限\n" +
                        "2. 关闭电池优化\n" +
                        "3. 允许后台运行")
                .setPositiveButton(R.string.go_to_settings, (dialog, which) -> {
                    // 先尝试打开自启动设置
                    if (!HuaweiDeviceHelper.openAutoStartSettings(activity)) {
                        // 如果失败，打开手机管家
                        HuaweiDeviceHelper.openSystemManager(activity);
                    }
                })
                .setNegativeButton(R.string.dont_show_again, (dialog, which) -> {
                    markHuaweiGuideShown(activity);
                })
                .setNeutralButton(R.string.cancel, null)
                .show();
    }

    /**
     * 显示电池优化引导对话框
     */
    public static void showBatteryOptimizationDialog(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle("电池优化")
                .setMessage("为确保后台播放稳定，建议关闭电池优化。")
                .setPositiveButton(R.string.go_to_settings, (dialog, which) -> {
                    requestIgnoreBatteryOptimizations(activity);
                })
                .setNegativeButton(R.string.dont_show_again, (dialog, which) -> {
                    markBatteryGuideShown(activity);
                })
                .setNeutralButton(R.string.cancel, null)
                .show();
    }
}
