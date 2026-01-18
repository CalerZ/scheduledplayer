package com.caleb.scheduledplayer.presentation.ui.log;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.caleb.scheduledplayer.R;
import com.caleb.scheduledplayer.util.AppLogger;

import java.util.List;

/**
 * 日志列表适配器
 */
public class LogAdapter extends RecyclerView.Adapter<LogAdapter.ViewHolder> {

    // 日志级别对应的颜色
    private static final int COLOR_DEBUG = Color.parseColor("#607D8B");   // 灰蓝色
    private static final int COLOR_INFO = Color.parseColor("#4CAF50");    // 绿色
    private static final int COLOR_WARN = Color.parseColor("#FF9800");    // 橙色
    private static final int COLOR_ERROR = Color.parseColor("#F44336");   // 红色
    private static final int COLOR_DEFAULT = Color.parseColor("#9E9E9E"); // 灰色

    private final List<AppLogger.LogEntry> logs;

    public LogAdapter(List<AppLogger.LogEntry> logs) {
        this.logs = logs;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_log, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppLogger.LogEntry entry = logs.get(position);
        holder.bind(entry);
    }

    @Override
    public int getItemCount() {
        return logs.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvLevel;
        private final TextView tvTimestamp;
        private final TextView tvTag;
        private final TextView tvMessage;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvLevel = itemView.findViewById(R.id.tvLevel);
            tvTimestamp = itemView.findViewById(R.id.tvTimestamp);
            tvTag = itemView.findViewById(R.id.tvTag);
            tvMessage = itemView.findViewById(R.id.tvMessage);
        }

        void bind(AppLogger.LogEntry entry) {
            tvLevel.setText(entry.level);
            tvTag.setText(entry.tag);
            tvMessage.setText(entry.message);
            
            // 显示时间（只显示时分秒毫秒部分）
            String time = entry.timestamp;
            if (time != null && time.length() > 11) {
                time = time.substring(11); // 去掉日期部分
            }
            tvTimestamp.setText(time);

            // 根据日志级别设置颜色
            int levelColor = getLevelColor(entry.level);
            setLevelBackground(tvLevel, levelColor);
            
            // ERROR 级别的消息也用红色
            if ("E".equals(entry.level)) {
                tvMessage.setTextColor(COLOR_ERROR);
            } else {
                tvMessage.setTextColor(itemView.getContext().getColor(R.color.on_surface));
            }
        }

        private int getLevelColor(String level) {
            if (level == null) return COLOR_DEFAULT;
            switch (level) {
                case "D": return COLOR_DEBUG;
                case "I": return COLOR_INFO;
                case "W": return COLOR_WARN;
                case "E": return COLOR_ERROR;
                default: return COLOR_DEFAULT;
            }
        }

        private void setLevelBackground(TextView textView, int color) {
            GradientDrawable drawable = new GradientDrawable();
            drawable.setShape(GradientDrawable.RECTANGLE);
            drawable.setCornerRadius(4f);
            drawable.setColor(color);
            textView.setBackground(drawable);
        }
    }
}
