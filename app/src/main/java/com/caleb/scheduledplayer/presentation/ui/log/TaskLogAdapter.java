package com.caleb.scheduledplayer.presentation.ui.log;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.caleb.scheduledplayer.R;
import com.caleb.scheduledplayer.data.entity.TaskLogEntity;
import com.caleb.scheduledplayer.util.LogErrorType;
import com.caleb.scheduledplayer.util.LogStatus;

import org.json.JSONArray;
import org.json.JSONException;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 任务日志列表适配器
 */
public class TaskLogAdapter extends ListAdapter<TaskLogEntity, TaskLogAdapter.LogViewHolder> {

    private static final SimpleDateFormat DATE_FORMAT = 
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    public TaskLogAdapter() {
        super(DIFF_CALLBACK);
    }

    private static final DiffUtil.ItemCallback<TaskLogEntity> DIFF_CALLBACK = 
            new DiffUtil.ItemCallback<TaskLogEntity>() {
        @Override
        public boolean areItemsTheSame(@NonNull TaskLogEntity oldItem, @NonNull TaskLogEntity newItem) {
            return oldItem.getId() == newItem.getId();
        }

        @Override
        public boolean areContentsTheSame(@NonNull TaskLogEntity oldItem, @NonNull TaskLogEntity newItem) {
            return oldItem.getStatus() == newItem.getStatus()
                    && oldItem.getStartTime() == newItem.getStartTime()
                    && (oldItem.getEndTime() == null ? newItem.getEndTime() == null 
                        : oldItem.getEndTime().equals(newItem.getEndTime()));
        }
    };

    @NonNull
    @Override
    public LogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_task_log, parent, false);
        return new LogViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull LogViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    static class LogViewHolder extends RecyclerView.ViewHolder {
        private final View viewStatus;
        private final ImageView imageStatus;
        private final TextView textTime;
        private final TextView textStatus;
        private final TextView textPlayInfo;
        private final TextView textError;
        private final TextView textDuration;

        LogViewHolder(@NonNull View itemView) {
            super(itemView);
            viewStatus = itemView.findViewById(R.id.viewStatus);
            imageStatus = itemView.findViewById(R.id.imageStatus);
            textTime = itemView.findViewById(R.id.textTime);
            textStatus = itemView.findViewById(R.id.textStatus);
            textPlayInfo = itemView.findViewById(R.id.textPlayInfo);
            textError = itemView.findViewById(R.id.textError);
            textDuration = itemView.findViewById(R.id.textDuration);
        }

        void bind(TaskLogEntity log) {
            // 设置时间
            String startTimeStr = DATE_FORMAT.format(new Date(log.getStartTime()));
            String endTimeStr = log.getEndTime() != null 
                    ? DATE_FORMAT.format(new Date(log.getEndTime())) 
                    : "进行中...";
            textTime.setText(startTimeStr + " - " + (log.getEndTime() != null 
                    ? DATE_FORMAT.format(new Date(log.getEndTime())).substring(11) : "进行中"));

            // 设置状态
            int statusColor;
            int statusIcon;
            String statusText;
            
            switch (log.getStatus()) {
                case LogStatus.SUCCESS:
                    statusColor = R.color.log_success;
                    statusIcon = android.R.drawable.ic_menu_send;
                    statusText = itemView.getContext().getString(R.string.log_status_success);
                    break;
                case LogStatus.FAILED:
                    statusColor = R.color.log_failed;
                    statusIcon = android.R.drawable.ic_delete;
                    statusText = itemView.getContext().getString(R.string.log_status_failed);
                    break;
                case LogStatus.IN_PROGRESS:
                default:
                    statusColor = R.color.log_in_progress;
                    statusIcon = android.R.drawable.ic_media_play;
                    statusText = itemView.getContext().getString(R.string.log_status_in_progress);
                    break;
            }

            int color = ContextCompat.getColor(itemView.getContext(), statusColor);
            viewStatus.setBackgroundColor(color);
            imageStatus.setImageTintList(ColorStateList.valueOf(color));
            imageStatus.setImageResource(statusIcon);
            textStatus.setText(statusText);
            textStatus.setBackgroundTintList(ColorStateList.valueOf(color));

            // 设置播放信息
            int playedCount = getPlayedFilesCount(log.getPlayedFiles());
            if (playedCount > 0) {
                textPlayInfo.setText(itemView.getContext().getString(R.string.log_played_files, playedCount));
            } else {
                textPlayInfo.setText(R.string.log_no_files_played);
            }

            // 设置错误信息
            if (log.getStatus() == LogStatus.FAILED && log.getErrorMessage() != null) {
                textError.setVisibility(View.VISIBLE);
                String errorTypeText = LogErrorType.getDisplayText(log.getErrorType());
                textError.setText(itemView.getContext().getString(R.string.log_error_reason, 
                        errorTypeText + " - " + log.getErrorMessage()));
            } else {
                textError.setVisibility(View.GONE);
            }

            // 设置执行时长
            long duration = log.getDuration();
            textDuration.setText(itemView.getContext().getString(R.string.log_duration, 
                    formatDuration(duration)));
        }

        private int getPlayedFilesCount(String playedFilesJson) {
            if (playedFilesJson == null || playedFilesJson.isEmpty()) {
                return 0;
            }
            try {
                JSONArray jsonArray = new JSONArray(playedFilesJson);
                return jsonArray.length();
            } catch (JSONException e) {
                return 0;
            }
        }

        private String formatDuration(long millis) {
            long hours = TimeUnit.MILLISECONDS.toHours(millis);
            long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
            long seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60;

            if (hours > 0) {
                return String.format(Locale.getDefault(), "%d小时%d分钟", hours, minutes);
            } else if (minutes > 0) {
                return String.format(Locale.getDefault(), "%d分钟%d秒", minutes, seconds);
            } else {
                return String.format(Locale.getDefault(), "%d秒", seconds);
            }
        }
    }
}
