package com.caleb.scheduledplayer.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

/**
 * 华为设备辅助工具类
 * 用于检测华为设备和处理华为特有的后台限制
 */
public class HuaweiDeviceHelper {

    private static final String TAG = "HuaweiDeviceHelper";

    // 华为设备制造商标识
    private static final String MANUFACTURER_HUAWEI = "HUAWEI";
    private static final String MANUFACTURER_HONOR = "HONOR";

    // 华为手机管家包名
    private static final String HUAWEI_SYSTEM_MANAGER = "com.huawei.systemmanager";
    
    // 自启动管理 Activity
    private static final String HUAWEI_AUTO_START_ACTIVITY = 
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity";
    
    // 电池优化 Activity
    private static final String HUAWEI_BATTERY_OPTIMIZE_ACTIVITY = 
            "com.huawei.systemmanager.power.ui.HwPowerManagerActivity";
    
    // 后台应用管理 Activity
    private static final String HUAWEI_PROTECTED_APPS_ACTIVITY = 
            "com.huawei.systemmanager.optimize.process.ProtectActivity";

    /**
     * 检测是否为华为或荣耀设备
     */
    public static boolean isHuaweiDevice() {
        String manufacturer = Build.MANUFACTURER.toUpperCase();
        return manufacturer.contains(MANUFACTURER_HUAWEI) || manufacturer.contains(MANUFACTURER_HONOR);
    }

    /**
     * 获取设备制造商
     */
    public static String getManufacturer() {
        return Build.MANUFACTURER;
    }

    /**
     * 获取 EMUI 版本
     */
    public static String getEmuiVersion() {
        try {
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            Object obj = clazz.getMethod("get", String.class).invoke(clazz, "ro.build.version.emui");
            return obj != null ? obj.toString() : "";
        } catch (Exception e) {
            Log.e(TAG, "获取 EMUI 版本失败", e);
            return "";
        }
    }

    /**
     * 跳转到华为自启动管理页面
     */
    public static boolean openAutoStartSettings(Context context) {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(HUAWEI_SYSTEM_MANAGER, HUAWEI_AUTO_START_ACTIVITY));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            
            if (isIntentAvailable(context, intent)) {
                context.startActivity(intent);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "跳转自启动管理失败", e);
        }
        return false;
    }

    /**
     * 跳转到华为电池优化页面
     */
    public static boolean openBatteryOptimizeSettings(Context context) {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(HUAWEI_SYSTEM_MANAGER, HUAWEI_BATTERY_OPTIMIZE_ACTIVITY));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            
            if (isIntentAvailable(context, intent)) {
                context.startActivity(intent);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "跳转电池优化失败", e);
        }
        return false;
    }

    /**
     * 跳转到华为后台应用管理页面
     */
    public static boolean openProtectedAppsSettings(Context context) {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(HUAWEI_SYSTEM_MANAGER, HUAWEI_PROTECTED_APPS_ACTIVITY));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            
            if (isIntentAvailable(context, intent)) {
                context.startActivity(intent);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "跳转后台应用管理失败", e);
        }
        return false;
    }

    /**
     * 跳转到华为手机管家
     */
    public static boolean openSystemManager(Context context) {
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(HUAWEI_SYSTEM_MANAGER);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "跳转手机管家失败", e);
        }
        return false;
    }

    /**
     * 检查 Intent 是否可用
     */
    private static boolean isIntentAvailable(Context context, Intent intent) {
        PackageManager pm = context.getPackageManager();
        return pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null;
    }

    /**
     * 获取华为设备信息摘要
     */
    public static String getDeviceInfo() {
        return "制造商: " + Build.MANUFACTURER + "\n"
                + "型号: " + Build.MODEL + "\n"
                + "Android 版本: " + Build.VERSION.RELEASE + "\n"
                + "EMUI 版本: " + getEmuiVersion();
    }
}
