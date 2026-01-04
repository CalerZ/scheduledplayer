package com.caleb.scheduledplayer.util;

import android.Manifest;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 蓝牙连接辅助类
 * 用于检测蓝牙音频设备连接状态
 */
public class BluetoothHelper {

    private static final String TAG = "BluetoothHelper";

    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;
    private final AudioManager audioManager;
    private BluetoothConnectionListener listener;
    private BroadcastReceiver bluetoothReceiver;
    private boolean isReceiverRegistered = false;
    
    // 记录最后连接的设备
    private BluetoothDevice lastConnectedDevice;

    /**
     * 蓝牙连接监听接口
     */
    public interface BluetoothConnectionListener {
        void onBluetoothAudioConnected();
        void onBluetoothAudioDisconnected();
        
        /**
         * 蓝牙设备断开连接（带设备信息）
         * @param device 断开的设备，可能为 null
         * @param deviceName 设备名称
         */
        default void onBluetoothDeviceDisconnected(BluetoothDevice device, String deviceName) {}
        
        /**
         * 蓝牙设备连接（带设备信息）
         * @param device 连接的设备，可能为 null
         * @param deviceName 设备名称
         */
        default void onBluetoothDeviceConnected(BluetoothDevice device, String deviceName) {}
    }

    public BluetoothHelper(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.bluetoothAdapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
    }

    /**
     * 检查是否有蓝牙权限
     */
    public boolean hasBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /**
     * 检查蓝牙是否可用
     */
    public boolean isBluetoothAvailable() {
        return bluetoothAdapter != null;
    }

    /**
     * 检查蓝牙是否已开启
     */
    public boolean isBluetoothEnabled() {
        if (!hasBluetoothPermission()) {
            return false;
        }
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    /**
     * 检查是否有蓝牙音频设备连接
     */
    public boolean isBluetoothAudioConnected() {
        if (!hasBluetoothPermission()) {
            Log.w(TAG, "No bluetooth permission");
            return false;
        }

        // 方法1：通过 AudioManager 检查音频输出设备
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo device : devices) {
                int type = device.getType();
                if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    Log.d(TAG, "Bluetooth audio device found: " + device.getProductName());
                    return true;
                }
            }
        }

        // 方法2：检查 A2DP 配置文件连接状态
        if (bluetoothAdapter != null) {
            try {
                int a2dpState = bluetoothAdapter.getProfileConnectionState(BluetoothProfile.A2DP);
                if (a2dpState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "A2DP profile connected");
                    return true;
                }
                
                int headsetState = bluetoothAdapter.getProfileConnectionState(BluetoothProfile.HEADSET);
                if (headsetState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "Headset profile connected");
                    return true;
                }
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception checking bluetooth state", e);
            }
        }

        return false;
    }

    /**
     * 获取已连接的蓝牙音频设备名称列表
     */
    public List<String> getConnectedBluetoothAudioDevices() {
        List<String> deviceNames = new ArrayList<>();
        
        if (!hasBluetoothPermission()) {
            return deviceNames;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo device : devices) {
                int type = device.getType();
                if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    CharSequence name = device.getProductName();
                    if (name != null && name.length() > 0) {
                        deviceNames.add(name.toString());
                    }
                }
            }
        }

        return deviceNames;
    }
    
    /**
     * 获取最后连接的蓝牙设备
     */
    public BluetoothDevice getLastConnectedDevice() {
        return lastConnectedDevice;
    }
    
    /**
     * 获取 BluetoothAdapter
     */
    public BluetoothAdapter getBluetoothAdapter() {
        return bluetoothAdapter;
    }

    /**
     * 开始监听蓝牙连接状态变化
     */
    public void startListening(BluetoothConnectionListener listener) {
        this.listener = listener;
        
        if (isReceiverRegistered) {
            return;
        }

        bluetoothReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;

                Log.d(TAG, "Bluetooth broadcast received: " + action);

                switch (action) {
                    case BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED:
                    case BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED:
                        int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                        handleConnectionStateChanged(state, device);
                        break;
                        
                    case BluetoothAdapter.ACTION_STATE_CHANGED:
                        int adapterState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                        if (adapterState == BluetoothAdapter.STATE_OFF) {
                            notifyDisconnected(null, "蓝牙已关闭");
                        }
                        break;
                        
                    case AudioManager.ACTION_AUDIO_BECOMING_NOISY:
                        // 音频设备断开（如蓝牙耳机断开）
                        if (!isBluetoothAudioConnected()) {
                            notifyDisconnected(lastConnectedDevice, getDeviceName(lastConnectedDevice));
                        }
                        break;
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(bluetoothReceiver, filter);
        }
        isReceiverRegistered = true;
        
        Log.d(TAG, "Started listening for bluetooth changes");
    }

    /**
     * 停止监听蓝牙连接状态变化
     */
    public void stopListening() {
        if (bluetoothReceiver != null && isReceiverRegistered) {
            try {
                context.unregisterReceiver(bluetoothReceiver);
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering receiver", e);
            }
            isReceiverRegistered = false;
        }
        listener = null;
        Log.d(TAG, "Stopped listening for bluetooth changes");
    }

    private void handleConnectionStateChanged(int state, BluetoothDevice device) {
        String deviceName = getDeviceName(device);
        
        switch (state) {
            case BluetoothProfile.STATE_CONNECTED:
                lastConnectedDevice = device;
                notifyConnected(device, deviceName);
                break;
            case BluetoothProfile.STATE_DISCONNECTED:
                // 再次检查是否还有其他蓝牙音频设备连接
                if (!isBluetoothAudioConnected()) {
                    notifyDisconnected(device, deviceName);
                }
                break;
        }
    }
    
    private String getDeviceName(BluetoothDevice device) {
        if (device == null) {
            return "未知设备";
        }
        
        if (!hasBluetoothPermission()) {
            return "蓝牙设备";
        }
        
        try {
            String name = device.getName();
            return name != null && !name.isEmpty() ? name : device.getAddress();
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception getting device name", e);
            return "蓝牙设备";
        }
    }

    private void notifyConnected(BluetoothDevice device, String deviceName) {
        if (listener != null) {
            listener.onBluetoothAudioConnected();
            listener.onBluetoothDeviceConnected(device, deviceName);
        }
    }

    private void notifyDisconnected(BluetoothDevice device, String deviceName) {
        if (listener != null) {
            listener.onBluetoothAudioDisconnected();
            listener.onBluetoothDeviceDisconnected(device, deviceName);
        }
    }
}
