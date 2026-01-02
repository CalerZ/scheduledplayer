package com.caleb.scheduledplayer.presentation.ui.permission;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.caleb.scheduledplayer.R;
import com.caleb.scheduledplayer.util.HuaweiDeviceHelper;
import com.caleb.scheduledplayer.util.PermissionHelper;

/**
 * 华为权限引导页面
 * 引导用户开启自启动、电池优化白名单等权限
 */
public class HuaweiPermissionGuideActivity extends AppCompatActivity {
    
    private static final String PREF_NAME = "huawei_permission_guide";
    private static final String KEY_DONT_SHOW_AGAIN = "dont_show_again";
    
    private TextView tvTitle;
    private TextView tvDescription;
    private TextView tvStep1;
    private TextView tvStep2;
    private TextView tvStep3;
    private Button btnAutoStart;
    private Button btnBatteryOptimization;
    private Button btnBackgroundActivity;
    private Button btnSkip;
    private CheckBox cbDontShowAgain;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_huawei_permission_guide);
        
        initViews();
        setupClickListeners();
    }
    
    private void initViews() {
        tvTitle = findViewById(R.id.tv_title);
        tvDescription = findViewById(R.id.tv_description);
        tvStep1 = findViewById(R.id.tv_step1);
        tvStep2 = findViewById(R.id.tv_step2);
        tvStep3 = findViewById(R.id.tv_step3);
        btnAutoStart = findViewById(R.id.btn_auto_start);
        btnBatteryOptimization = findViewById(R.id.btn_battery_optimization);
        btnBackgroundActivity = findViewById(R.id.btn_background_activity);
        btnSkip = findViewById(R.id.btn_skip);
        cbDontShowAgain = findViewById(R.id.cb_dont_show_again);
        
        // 设置标题和描述
        tvTitle.setText("后台运行设置");
        tvDescription.setText("为了确保定时播放功能正常工作，请按以下步骤设置权限：");
        
        tvStep1.setText("1. 开启自启动权限，允许应用开机后自动启动");
        tvStep2.setText("2. 关闭电池优化，防止系统限制后台运行");
        tvStep3.setText("3. 允许后台活动，确保应用在后台正常工作");
    }
    
    private void setupClickListeners() {
        btnAutoStart.setOnClickListener(v -> {
            HuaweiDeviceHelper.openAutoStartSettings(this);
        });
        
        btnBatteryOptimization.setOnClickListener(v -> {
            PermissionHelper.requestBatteryOptimizationExemption(this);
        });
        
        btnBackgroundActivity.setOnClickListener(v -> {
            HuaweiDeviceHelper.openBatteryOptimizeSettings(this);
        });
        
        btnSkip.setOnClickListener(v -> {
            if (cbDontShowAgain.isChecked()) {
                setDontShowAgain(true);
            }
            finish();
        });
    }
    
    /**
     * 检查是否应该显示引导页面
     */
    public static boolean shouldShowGuide(Context context) {
        // 只在华为设备上显示
        if (!HuaweiDeviceHelper.isHuaweiDevice()) {
            return false;
        }
        
        // 检查用户是否选择了不再显示
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        return !prefs.getBoolean(KEY_DONT_SHOW_AGAIN, false);
    }
    
    /**
     * 设置不再显示
     */
    private void setDontShowAgain(boolean dontShow) {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_DONT_SHOW_AGAIN, dontShow).apply();
    }
    
    /**
     * 重置不再显示设置
     */
    public static void resetDontShowAgain(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_DONT_SHOW_AGAIN, false).apply();
    }
    
    /**
     * 启动引导页面
     */
    public static void start(Context context) {
        Intent intent = new Intent(context, HuaweiPermissionGuideActivity.class);
        context.startActivity(intent);
    }
}
