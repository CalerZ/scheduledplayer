package com.caleb.scheduledplayer.util;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 蓝牙自动重连管理器
 * 用于在蓝牙断开后尝试自动重新连接
 */
public class BluetoothReconnectManager {

    private static final String TAG = "BluetoothReconnect";
    
    // 重连配置
    private static final int MAX_RECONNECT_ATTEMPTS = 3;
    private static final long RECONNECT_DELAY_MS = 2000; // 2秒后开始重连
    private static final long RECONNECT_INTERVAL_MS = 5000; // 每次重连间隔5秒

    private final Context context;
    private final BluetoothHelper bluetoothHelper;
    private final Handler handler;
    
    private BluetoothDevice lastConnectedDevice;
    private BluetoothA2dp bluetoothA2dp;
    private boolean isReconnecting = false;
    private int reconnectAttempts = 0;
    private ReconnectCallback callback;
    
    private Runnable reconnectRunnable;

    public interface ReconnectCallback {
        void onReconnectSuccess();
        void onReconnectFailed(String reason);
        void onReconnectAttempt(int attempt, int maxAttempts);
    }

    public BluetoothReconnectManager(Context context, BluetoothHelper bluetoothHelper) {
        this.context = context.getApplicationContext();
        this.bluetoothHelper = bluetoothHelper;
        this.handler = new Handler(Looper.getMainLooper());
        
        // 获取 A2DP 代理
        initA2dpProxy();
    }

    private void initA2dpProxy() {
        BluetoothAdapter adapter = bluetoothHelper.getBluetoothAdapter();
        if (adapter == null) {
            Log.w(TAG, "BluetoothAdapter is null");
            return;
        }

        adapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.A2DP) {
                    bluetoothA2dp = (BluetoothA2dp) proxy;
                    Log.d(TAG, "A2DP proxy connected");
                }
            }

            @Override
            public void onServiceDisconnected(int profile) {
                if (profile == BluetoothProfile.A2DP) {
                    bluetoothA2dp = null;
                    Log.d(TAG, "A2DP proxy disconnected");
                }
            }
        }, BluetoothProfile.A2DP);
    }

    /**
     * 记录断开的设备，准备重连
     */
    public void saveDisconnectedDevice(BluetoothDevice device) {
        if (device != null) {
            this.lastConnectedDevice = device;
            Log.d(TAG, "Saved disconnected device: " + getDeviceName(device));
        }
    }

    /**
     * 获取上次连接的设备
     */
    public BluetoothDevice getLastConnectedDevice() {
        return lastConnectedDevice;
    }

    /**
     * 开始尝试重连
     */
    public void startReconnect(ReconnectCallback callback) {
        if (isReconnecting) {
            Log.d(TAG, "Already reconnecting");
            return;
        }

        if (lastConnectedDevice == null) {
            Log.w(TAG, "No device to reconnect");
            if (callback != null) {
                callback.onReconnectFailed("没有可重连的设备");
            }
            return;
        }

        if (!bluetoothHelper.hasBluetoothPermission()) {
            Log.w(TAG, "No bluetooth permission");
            if (callback != null) {
                callback.onReconnectFailed("没有蓝牙权限");
            }
            return;
        }

        if (!bluetoothHelper.isBluetoothEnabled()) {
            Log.w(TAG, "Bluetooth is disabled");
            if (callback != null) {
                callback.onReconnectFailed("蓝牙未开启");
            }
            return;
        }

        this.callback = callback;
        this.isReconnecting = true;
        this.reconnectAttempts = 0;

        Log.d(TAG, "Starting reconnect to: " + getDeviceName(lastConnectedDevice));
        
        // 延迟开始重连
        handler.postDelayed(() -> attemptReconnect(), RECONNECT_DELAY_MS);
    }

    /**
     * 停止重连
     */
    public void stopReconnect() {
        isReconnecting = false;
        reconnectAttempts = 0;
        if (reconnectRunnable != null) {
            handler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
        Log.d(TAG, "Reconnect stopped");
    }

    /**
     * 检查是否正在重连
     */
    public boolean isReconnecting() {
        return isReconnecting;
    }

    private void attemptReconnect() {
        if (!isReconnecting) {
            return;
        }

        // 检查是否已经连接
        if (bluetoothHelper.isBluetoothAudioConnected()) {
            Log.d(TAG, "Device already connected");
            onReconnectSuccess();
            return;
        }

        reconnectAttempts++;
        
        if (callback != null) {
            callback.onReconnectAttempt(reconnectAttempts, MAX_RECONNECT_ATTEMPTS);
        }

        Log.d(TAG, "Reconnect attempt " + reconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS);

        // 尝试通过反射调用 connect 方法
        boolean connectInitiated = tryConnect(lastConnectedDevice);

        if (connectInitiated) {
            // 等待一段时间后检查连接状态
            reconnectRunnable = () -> {
                if (!isReconnecting) return;
                
                if (bluetoothHelper.isBluetoothAudioConnected()) {
                    onReconnectSuccess();
                } else if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                    // 继续尝试
                    attemptReconnect();
                } else {
                    // 达到最大尝试次数
                    onReconnectFailed("已达到最大重连次数");
                }
            };
            handler.postDelayed(reconnectRunnable, RECONNECT_INTERVAL_MS);
        } else {
            // 连接方法调用失败
            if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                reconnectRunnable = this::attemptReconnect;
                handler.postDelayed(reconnectRunnable, RECONNECT_INTERVAL_MS);
            } else {
                onReconnectFailed("无法发起连接请求");
            }
        }
    }

    /**
     * 通过反射尝试连接设备
     * 注意：这是一个尽力而为的方法，不保证在所有设备上都有效
     */
    private boolean tryConnect(BluetoothDevice device) {
        if (bluetoothA2dp == null) {
            Log.w(TAG, "A2DP proxy not available");
            return false;
        }

        try {
            // 使用反射调用 connect 方法
            Method connectMethod = BluetoothA2dp.class.getMethod("connect", BluetoothDevice.class);
            connectMethod.setAccessible(true);
            Boolean result = (Boolean) connectMethod.invoke(bluetoothA2dp, device);
            Log.d(TAG, "Connect method invoked, result: " + result);
            return result != null && result;
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "Connect method not found (expected on some devices)");
        } catch (SecurityException e) {
            Log.w(TAG, "Security exception calling connect", e);
        } catch (Exception e) {
            Log.e(TAG, "Error calling connect method", e);
        }

        return false;
    }

    private void onReconnectSuccess() {
        isReconnecting = false;
        reconnectAttempts = 0;
        Log.d(TAG, "Reconnect successful");
        
        if (callback != null) {
            callback.onReconnectSuccess();
        }
    }

    private void onReconnectFailed(String reason) {
        isReconnecting = false;
        reconnectAttempts = 0;
        Log.d(TAG, "Reconnect failed: " + reason);
        
        if (callback != null) {
            callback.onReconnectFailed(reason);
        }
    }

    private String getDeviceName(BluetoothDevice device) {
        if (device == null) return "null";
        
        try {
            String name = device.getName();
            return name != null ? name : device.getAddress();
        } catch (SecurityException e) {
            return device.getAddress();
        }
    }

    /**
     * 释放资源
     */
    public void release() {
        stopReconnect();
        
        if (bluetoothA2dp != null) {
            BluetoothAdapter adapter = bluetoothHelper.getBluetoothAdapter();
            if (adapter != null) {
                adapter.closeProfileProxy(BluetoothProfile.A2DP, bluetoothA2dp);
            }
            bluetoothA2dp = null;
        }
    }
}
