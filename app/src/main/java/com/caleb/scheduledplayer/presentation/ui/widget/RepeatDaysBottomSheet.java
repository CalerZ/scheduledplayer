package com.caleb.scheduledplayer.presentation.ui.widget;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;

import com.caleb.scheduledplayer.R;
import com.caleb.scheduledplayer.data.entity.TaskEntity;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * 重复日期选择底部抽屉
 */
public class RepeatDaysBottomSheet {

    public interface OnDaysSelectedListener {
        void onDaysSelected(int repeatDays);
    }

    public static class Builder {
        private final Context context;
        private int initialDays = 0;
        private OnDaysSelectedListener listener;

        public Builder(Context context) {
            this.context = context;
        }

        public Builder setInitialDays(int days) {
            this.initialDays = days;
            return this;
        }

        public Builder setOnDaysSelectedListener(OnDaysSelectedListener listener) {
            this.listener = listener;
            return this;
        }

        public void show() {
            BottomSheetDialog dialog = new BottomSheetDialog(context);
            View view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_repeat_days, null);
            dialog.setContentView(view);

            // 获取所有复选框
            CheckBox checkboxSelectAll = view.findViewById(R.id.checkboxSelectAll);
            CheckBox checkboxMonday = view.findViewById(R.id.checkboxMonday);
            CheckBox checkboxTuesday = view.findViewById(R.id.checkboxTuesday);
            CheckBox checkboxWednesday = view.findViewById(R.id.checkboxWednesday);
            CheckBox checkboxThursday = view.findViewById(R.id.checkboxThursday);
            CheckBox checkboxFriday = view.findViewById(R.id.checkboxFriday);
            CheckBox checkboxSaturday = view.findViewById(R.id.checkboxSaturday);
            CheckBox checkboxSunday = view.findViewById(R.id.checkboxSunday);

            CheckBox[] dayCheckboxes = {
                    checkboxMonday, checkboxTuesday, checkboxWednesday,
                    checkboxThursday, checkboxFriday, checkboxSaturday, checkboxSunday
            };

            int[] dayFlags = {
                    TaskEntity.MONDAY, TaskEntity.TUESDAY, TaskEntity.WEDNESDAY,
                    TaskEntity.THURSDAY, TaskEntity.FRIDAY, TaskEntity.SATURDAY, TaskEntity.SUNDAY
            };

            // 初始化选中状态
            for (int i = 0; i < dayCheckboxes.length; i++) {
                dayCheckboxes[i].setChecked((initialDays & dayFlags[i]) != 0);
            }
            updateSelectAllState(checkboxSelectAll, dayCheckboxes);

            // 全选点击事件
            LinearLayout layoutSelectAll = view.findViewById(R.id.layoutSelectAll);
            layoutSelectAll.setOnClickListener(v -> {
                boolean newState = !checkboxSelectAll.isChecked();
                checkboxSelectAll.setChecked(newState);
                for (CheckBox cb : dayCheckboxes) {
                    cb.setChecked(newState);
                }
            });

            // 各天点击事件
            int[] layoutIds = {
                    R.id.layoutMonday, R.id.layoutTuesday, R.id.layoutWednesday,
                    R.id.layoutThursday, R.id.layoutFriday, R.id.layoutSaturday, R.id.layoutSunday
            };

            for (int i = 0; i < layoutIds.length; i++) {
                final int index = i;
                view.findViewById(layoutIds[i]).setOnClickListener(v -> {
                    dayCheckboxes[index].setChecked(!dayCheckboxes[index].isChecked());
                    updateSelectAllState(checkboxSelectAll, dayCheckboxes);
                });
            }

            // 确定按钮
            view.findViewById(R.id.buttonConfirm).setOnClickListener(v -> {
                int selectedDays = 0;
                for (int i = 0; i < dayCheckboxes.length; i++) {
                    if (dayCheckboxes[i].isChecked()) {
                        selectedDays |= dayFlags[i];
                    }
                }
                if (listener != null) {
                    listener.onDaysSelected(selectedDays);
                }
                dialog.dismiss();
            });

            dialog.show();
        }

        private void updateSelectAllState(CheckBox selectAll, CheckBox[] dayCheckboxes) {
            boolean allChecked = true;
            for (CheckBox cb : dayCheckboxes) {
                if (!cb.isChecked()) {
                    allChecked = false;
                    break;
                }
            }
            selectAll.setChecked(allChecked);
        }
    }

    /**
     * 将重复日期转换为显示文本
     */
    public static String formatRepeatDays(int repeatDays) {
        if (repeatDays == 0) {
            return "不重复";
        }

        // 检查是否每天
        int allDays = TaskEntity.MONDAY | TaskEntity.TUESDAY | TaskEntity.WEDNESDAY
                | TaskEntity.THURSDAY | TaskEntity.FRIDAY | TaskEntity.SATURDAY | TaskEntity.SUNDAY;
        if (repeatDays == allDays) {
            return "每天";
        }

        // 检查是否工作日
        int weekdays = TaskEntity.MONDAY | TaskEntity.TUESDAY | TaskEntity.WEDNESDAY
                | TaskEntity.THURSDAY | TaskEntity.FRIDAY;
        if (repeatDays == weekdays) {
            return "工作日";
        }

        // 检查是否周末
        int weekend = TaskEntity.SATURDAY | TaskEntity.SUNDAY;
        if (repeatDays == weekend) {
            return "周末";
        }

        // 列出具体天数
        StringBuilder sb = new StringBuilder();
        String[] dayNames = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        int[] dayFlags = {
                TaskEntity.MONDAY, TaskEntity.TUESDAY, TaskEntity.WEDNESDAY,
                TaskEntity.THURSDAY, TaskEntity.FRIDAY, TaskEntity.SATURDAY, TaskEntity.SUNDAY
        };

        for (int i = 0; i < dayFlags.length; i++) {
            if ((repeatDays & dayFlags[i]) != 0) {
                if (sb.length() > 0) {
                    sb.append("、");
                }
                sb.append(dayNames[i]);
            }
        }

        return sb.toString();
    }
}
