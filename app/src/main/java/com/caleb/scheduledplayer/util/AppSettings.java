package com.caleb.scheduledplayer.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 全局设置管理类
 * 使用 SharedPreferences 存储应用设置
 */
public class AppSettings {

    private static final String PREF_NAME = "app_settings";
    
    // 设置项 Key
    private static final String KEY_KEEP_BLUETOOTH_ALIVE = "keep_bluetooth_alive";
    private static final String KEY_BLUETOOTH_AUTO_RECONNECT = "bluetooth_auto_reconnect";
    
    private final SharedPreferences prefs;
    
    public AppSettings(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }
    
    /**
     * 获取是否保持蓝牙活跃
     * 默认值：true
     */
    public boolean isKeepBluetoothAlive() {
        return prefs.getBoolean(KEY_KEEP_BLUETOOTH_ALIVE, true);
    }
    
    /**
     * 设置是否保持蓝牙活跃
     */
    public void setKeepBluetoothAlive(boolean enabled) {
        prefs.edit().putBoolean(KEY_KEEP_BLUETOOTH_ALIVE, enabled).apply();
    }
    
    /**
     * 获取是否蓝牙自动重连
     * 默认值：true
     */
    public boolean isBluetoothAutoReconnect() {
        return prefs.getBoolean(KEY_BLUETOOTH_AUTO_RECONNECT, true);
    }
    
    /**
     * 设置是否蓝牙自动重连
     */
    public void setBluetoothAutoReconnect(boolean enabled) {
        prefs.edit().putBoolean(KEY_BLUETOOTH_AUTO_RECONNECT, enabled).apply();
    }
}
