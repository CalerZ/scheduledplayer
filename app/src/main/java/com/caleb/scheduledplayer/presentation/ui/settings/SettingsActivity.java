package com.caleb.scheduledplayer.presentation.ui.settings;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

import com.caleb.scheduledplayer.R;
import com.caleb.scheduledplayer.databinding.ActivitySettingsBinding;
import com.caleb.scheduledplayer.service.player.AudioPlaybackService;
import com.caleb.scheduledplayer.util.AppSettings;

/**
 * 设置页面
 */
public class SettingsActivity extends AppCompatActivity {

    private ActivitySettingsBinding binding;
    private AppSettings appSettings;
    
    // 播放服务绑定
    private AudioPlaybackService playbackService;
    private boolean serviceBound = false;
    
    // 状态刷新
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            updateStatusIndicators();
            statusHandler.postDelayed(this, 1000); // 每秒刷新
        }
    };
    
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            AudioPlaybackService.LocalBinder binder = (AudioPlaybackService.LocalBinder) service;
            playbackService = binder.getService();
            serviceBound = true;
            updateStatusIndicators();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            playbackService = null;
            serviceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        appSettings = new AppSettings(this);
        
        setupToolbar();
        setupSwitches();
        bindPlaybackService();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // 开始定时刷新状态
        statusHandler.post(statusRefreshRunnable);
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        // 停止定时刷新
        statusHandler.removeCallbacks(statusRefreshRunnable);
    }

    private void setupToolbar() {
        binding.toolbar.setNavigationOnClickListener(v -> finish());
    }

    private void setupSwitches() {
        // 初始化开关状态
        binding.switchKeepBluetoothAlive.setChecked(appSettings.isKeepBluetoothAlive());
        binding.switchBluetoothAutoReconnect.setChecked(appSettings.isBluetoothAutoReconnect());

        // 保持蓝牙活跃开关
        binding.switchKeepBluetoothAlive.setOnCheckedChangeListener((buttonView, isChecked) -> {
            appSettings.setKeepBluetoothAlive(isChecked);
            // 通知服务更新设置
            notifyServiceSettingsChanged();
            // 立即更新状态显示
            updateStatusIndicators();
        });

        // 蓝牙自动重连开关
        binding.switchBluetoothAutoReconnect.setOnCheckedChangeListener((buttonView, isChecked) -> {
            appSettings.setBluetoothAutoReconnect(isChecked);
            // 通知服务更新设置
            notifyServiceSettingsChanged();
        });
    }
    
    private void updateStatusIndicators() {
        if (!serviceBound || playbackService == null) {
            // 服务未绑定，显示未知状态
            binding.textSilentAudioStatus.setText(R.string.settings_status_unknown);
            binding.indicatorSilentAudio.setBackgroundResource(R.drawable.indicator_status_off);
            binding.textBluetoothStatus.setText(R.string.settings_status_unknown);
            binding.indicatorBluetooth.setBackgroundResource(R.drawable.indicator_status_off);
            return;
        }
        
        // 更新静音音频状态
        AudioPlaybackService.SilentAudioStatus silentStatus = playbackService.getSilentAudioStatus();
        switch (silentStatus) {
            case PLAYING:
                binding.textSilentAudioStatus.setText(R.string.settings_status_playing);
                binding.indicatorSilentAudio.setBackgroundResource(R.drawable.indicator_status_on);
                break;
            case STOPPED:
                binding.textSilentAudioStatus.setText(R.string.settings_status_stopped);
                binding.indicatorSilentAudio.setBackgroundResource(R.drawable.indicator_status_off);
                break;
            case DISABLED:
                binding.textSilentAudioStatus.setText(R.string.settings_status_disabled);
                binding.indicatorSilentAudio.setBackgroundResource(R.drawable.indicator_status_off);
                break;
            case NO_BLUETOOTH:
                binding.textSilentAudioStatus.setText(R.string.settings_status_no_bluetooth);
                binding.indicatorSilentAudio.setBackgroundResource(R.drawable.indicator_status_off);
                break;
        }
        
        // 更新蓝牙连接状态
        if (playbackService.isBluetoothConnected()) {
            String deviceName = playbackService.getConnectedBluetoothDeviceName();
            if (deviceName != null && !deviceName.isEmpty()) {
                binding.textBluetoothStatus.setText(getString(R.string.settings_bluetooth_connected, deviceName));
            } else {
                binding.textBluetoothStatus.setText(getString(R.string.settings_bluetooth_connected, "未知设备"));
            }
            binding.indicatorBluetooth.setBackgroundResource(R.drawable.indicator_status_on);
        } else {
            binding.textBluetoothStatus.setText(R.string.settings_bluetooth_not_connected);
            binding.indicatorBluetooth.setBackgroundResource(R.drawable.indicator_status_off);
        }
    }
    
    private void bindPlaybackService() {
        Intent intent = new Intent(this, AudioPlaybackService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }
    
    private void notifyServiceSettingsChanged() {
        if (serviceBound && playbackService != null) {
            playbackService.onSettingsChanged();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        statusHandler.removeCallbacks(statusRefreshRunnable);
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        binding = null;
    }
}
