package com.caleb.scheduledplayer.presentation.ui.widget;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.NumberPicker;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.caleb.scheduledplayer.R;

/**
 * 滚轮式时间选择器对话框
 */
public class WheelTimePickerDialog extends Dialog {

    private NumberPicker hourPicker;
    private NumberPicker minutePicker;
    private TextView titleView;
    private Button cancelButton;
    private Button confirmButton;

    private int selectedHour = 0;
    private int selectedMinute = 0;
    private String title = "选择时间";
    private OnTimeSelectedListener listener;

    public interface OnTimeSelectedListener {
        void onTimeSelected(int hour, int minute);
    }

    public WheelTimePickerDialog(@NonNull Context context) {
        super(context, R.style.WheelPickerDialogTheme);
    }

    public WheelTimePickerDialog setTitle(String title) {
        this.title = title;
        return this;
    }

    public WheelTimePickerDialog setHour(int hour) {
        this.selectedHour = hour;
        return this;
    }

    public WheelTimePickerDialog setMinute(int minute) {
        this.selectedMinute = minute;
        return this;
    }

    public WheelTimePickerDialog setOnTimeSelectedListener(OnTimeSelectedListener listener) {
        this.listener = listener;
        return this;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_wheel_time_picker);

        // 设置对话框从底部弹出
        Window window = getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            WindowManager.LayoutParams params = window.getAttributes();
            params.windowAnimations = R.style.BottomDialogAnimation;
            window.setAttributes(params);
        }

        initViews();
        setupPickers();
    }

    private void initViews() {
        titleView = findViewById(R.id.text_title);
        hourPicker = findViewById(R.id.picker_hour);
        minutePicker = findViewById(R.id.picker_minute);
        cancelButton = findViewById(R.id.button_cancel);
        confirmButton = findViewById(R.id.button_confirm);

        titleView.setText(title);

        cancelButton.setOnClickListener(v -> dismiss());
        confirmButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onTimeSelected(hourPicker.getValue(), minutePicker.getValue());
            }
            dismiss();
        });
    }

    private void setupPickers() {
        // 设置小时选择器 (0-23)
        hourPicker.setMinValue(0);
        hourPicker.setMaxValue(23);
        hourPicker.setValue(selectedHour);
        hourPicker.setWrapSelectorWheel(true);
        hourPicker.setFormatter(value -> String.format("%02d", value));

        // 设置分钟选择器 (0-59)
        minutePicker.setMinValue(0);
        minutePicker.setMaxValue(59);
        minutePicker.setValue(selectedMinute);
        minutePicker.setWrapSelectorWheel(true);
        minutePicker.setFormatter(value -> String.format("%02d", value));

        // 修复 NumberPicker 格式化显示问题
        fixNumberPickerDisplay(hourPicker);
        fixNumberPickerDisplay(minutePicker);
    }

    /**
     * 修复 NumberPicker 初始显示格式化问题
     */
    private void fixNumberPickerDisplay(NumberPicker picker) {
        try {
            // 通过反射获取内部的 EditText 并更新显示
            java.lang.reflect.Field field = NumberPicker.class.getDeclaredField("mInputText");
            field.setAccessible(true);
            TextView inputText = (TextView) field.get(picker);
            if (inputText != null) {
                inputText.setVisibility(View.INVISIBLE);
            }
        } catch (Exception e) {
            // 忽略反射错误
        }
    }

    /**
     * Builder 模式
     */
    public static class Builder {
        private final Context context;
        private int hour = 0;
        private int minute = 0;
        private String title = "选择时间";
        private OnTimeSelectedListener listener;

        public Builder(Context context) {
            this.context = context;
        }

        public Builder setHour(int hour) {
            this.hour = hour;
            return this;
        }

        public Builder setMinute(int minute) {
            this.minute = minute;
            return this;
        }

        public Builder setTitle(String title) {
            this.title = title;
            return this;
        }

        public Builder setOnTimeSelectedListener(OnTimeSelectedListener listener) {
            this.listener = listener;
            return this;
        }

        public WheelTimePickerDialog build() {
            WheelTimePickerDialog dialog = new WheelTimePickerDialog(context);
            dialog.setTitle(title);
            dialog.setHour(hour);
            dialog.setMinute(minute);
            dialog.setOnTimeSelectedListener(listener);
            return dialog;
        }

        public void show() {
            build().show();
        }
    }
}
