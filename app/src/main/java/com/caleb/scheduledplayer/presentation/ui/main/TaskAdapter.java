package com.caleb.scheduledplayer.presentation.ui.main;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.caleb.scheduledplayer.R;
import com.caleb.scheduledplayer.data.entity.TaskEntity;
import com.caleb.scheduledplayer.databinding.ItemTaskBinding;
import com.caleb.scheduledplayer.service.player.AudioPlaybackService;

import java.util.HashMap;
import java.util.Map;

/**
 * 任务列表适配器
 */
public class TaskAdapter extends ListAdapter<TaskEntity, TaskAdapter.TaskViewHolder> {

    private final OnTaskClickListener clickListener;
    private final OnTaskEnabledChangeListener enabledChangeListener;
    private final OnTaskLongClickListener longClickListener;
    private OnPlayPauseClickListener playPauseClickListener;
    
    // 所有任务的播放状态（taskId -> PlaybackState）
    private Map<Long, AudioPlaybackService.PlaybackState> playbackStates = new HashMap<>();

    public interface OnTaskClickListener {
        void onTaskClick(TaskEntity task);
    }

    public interface OnTaskEnabledChangeListener {
        void onEnabledChange(TaskEntity task, boolean enabled);
    }

    public interface OnTaskLongClickListener {
        void onTaskLongClick(TaskEntity task);
    }
    
    public interface OnPlayPauseClickListener {
        void onPlayPauseClick(long taskId, boolean isPaused);
    }

    public TaskAdapter(OnTaskClickListener clickListener, 
                       OnTaskEnabledChangeListener enabledChangeListener,
                       OnTaskLongClickListener longClickListener) {
        super(DIFF_CALLBACK);
        this.clickListener = clickListener;
        this.enabledChangeListener = enabledChangeListener;
        this.longClickListener = longClickListener;
    }
    
    public void setPlayPauseClickListener(OnPlayPauseClickListener listener) {
        this.playPauseClickListener = listener;
    }
    
    /**
     * 更新播放状态（兼容旧接口）
     */
    public void updatePlaybackState(AudioPlaybackService.PlaybackState state) {
        if (state != null && state.taskId != -1) {
            Map<Long, AudioPlaybackService.PlaybackState> singleState = new HashMap<>();
            singleState.put(state.taskId, state);
            updateAllPlaybackStates(singleState);
        } else {
            // 清空所有状态
            updateAllPlaybackStates(new HashMap<>());
        }
    }
    
    /**
     * 更新所有任务的播放状态
     */
    public void updateAllPlaybackStates(Map<Long, AudioPlaybackService.PlaybackState> states) {
        Map<Long, AudioPlaybackService.PlaybackState> oldStates = this.playbackStates;
        this.playbackStates = states != null ? states : new HashMap<>();
        
        // 找到需要更新的任务位置
        for (int i = 0; i < getItemCount(); i++) {
            TaskEntity task = getItem(i);
            boolean wasPlaying = oldStates.containsKey(task.getId());
            boolean isPlaying = playbackStates.containsKey(task.getId());
            
            if (wasPlaying || isPlaying) {
                notifyItemChanged(i, "playback");
            }
        }
    }

    private static final DiffUtil.ItemCallback<TaskEntity> DIFF_CALLBACK = new DiffUtil.ItemCallback<TaskEntity>() {
        @Override
        public boolean areItemsTheSame(@NonNull TaskEntity oldItem, @NonNull TaskEntity newItem) {
            return oldItem.getId() == newItem.getId();
        }

        @Override
        public boolean areContentsTheSame(@NonNull TaskEntity oldItem, @NonNull TaskEntity newItem) {
            return oldItem.getName().equals(newItem.getName())
                    && oldItem.isEnabled() == newItem.isEnabled()
                    && oldItem.getStartTime().equals(newItem.getStartTime())
                    && oldItem.getEndTime().equals(newItem.getEndTime())
                    && oldItem.getPlayMode() == newItem.getPlayMode()
                    && oldItem.getRepeatDays() == newItem.getRepeatDays()
                    && oldItem.isAllDayPlay() == newItem.isAllDayPlay()
                    && oldItem.getUpdatedAt() == newItem.getUpdatedAt();
        }
    };

    @NonNull
    @Override
    public TaskViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemTaskBinding binding = ItemTaskBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new TaskViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull TaskViewHolder holder, int position) {
        holder.bind(getItem(position));
    }
    
    @Override
    public void onBindViewHolder(@NonNull TaskViewHolder holder, int position, @NonNull java.util.List<Object> payloads) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position);
        } else {
            // 只更新播放状态
            holder.updatePlaybackState(getItem(position));
        }
    }

    class TaskViewHolder extends RecyclerView.ViewHolder {
        private final ItemTaskBinding binding;

        TaskViewHolder(ItemTaskBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(TaskEntity task) {
            // 任务名称
            binding.textTaskName.setText(task.getName());

            // 播放时间
            String timeText;
            if (task.isAllDayPlay()) {
                timeText = "全天";
            } else {
                timeText = task.getStartTime() + " - " + task.getEndTime();
            }
            binding.textTaskTime.setText(timeText);

            // 播放模式
            String modeText = getPlayModeText(task.getPlayMode());
            binding.textPlayMode.setText(" · " + modeText);

            // 重复日期
            String repeatText = getRepeatDaysText(task.getRepeatDays());
            binding.textRepeatDays.setText(repeatText);

            // 状态指示器颜色
            int statusColor = task.isEnabled()
                    ? itemView.getContext().getColor(R.color.task_enabled)
                    : itemView.getContext().getColor(R.color.task_disabled);
            binding.viewStatus.setBackgroundColor(statusColor);

            // 启用开关
            binding.switchEnabled.setOnCheckedChangeListener(null);
            binding.switchEnabled.setChecked(task.isEnabled());
            binding.switchEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (enabledChangeListener != null) {
                    enabledChangeListener.onEnabledChange(task, isChecked);
                }
            });

            // 点击事件
            itemView.setOnClickListener(v -> {
                if (clickListener != null) {
                    clickListener.onTaskClick(task);
                }
            });

            // 长按事件
            itemView.setOnLongClickListener(v -> {
                if (longClickListener != null) {
                    longClickListener.onTaskLongClick(task);
                    return true;
                }
                return false;
            });
            
            // 播放/暂停按钮点击
            binding.buttonPlayPause.setOnClickListener(v -> {
                if (playPauseClickListener != null) {
                    AudioPlaybackService.PlaybackState state = playbackStates.get(task.getId());
                    if (state != null) {
                        playPauseClickListener.onPlayPauseClick(task.getId(), state.isPaused);
                    }
                }
            });
            
            // 更新播放状态
            updatePlaybackState(task);
        }
        
        void updatePlaybackState(TaskEntity task) {
            // 从 map 中获取该任务的播放状态
            AudioPlaybackService.PlaybackState state = playbackStates.get(task.getId());
            boolean isThisTaskPlaying = state != null && state.taskId != -1;
            
            if (isThisTaskPlaying) {
                binding.layoutPlayback.setVisibility(View.VISIBLE);
                
                // 歌曲名
                if (state.currentSongName != null) {
                    binding.textSongName.setText(state.currentSongName);
                } else {
                    binding.textSongName.setText("正在播放...");
                }
                
                // 时间
                binding.textPlayTime.setText(formatTime(state.currentPosition) 
                        + " / " + formatTime(state.duration));
                
                // 进度条
                if (state.duration > 0) {
                    int progress = (int) ((state.currentPosition * 100L) / state.duration);
                    binding.progressBar.setProgress(progress);
                } else {
                    binding.progressBar.setProgress(0);
                }
                
                // 播放/暂停按钮图标
                binding.buttonPlayPause.setImageResource(
                        state.isPaused ? R.drawable.ic_play : R.drawable.ic_pause);
            } else {
                binding.layoutPlayback.setVisibility(View.GONE);
            }
        }
        
        private String formatTime(int milliseconds) {
            int seconds = milliseconds / 1000;
            int minutes = seconds / 60;
            seconds = seconds % 60;
            return String.format("%02d:%02d", minutes, seconds);
        }

        private String getPlayModeText(int playMode) {
            switch (playMode) {
                case TaskEntity.PLAY_MODE_RANDOM:
                    return itemView.getContext().getString(R.string.play_mode_random);
                case TaskEntity.PLAY_MODE_LOOP:
                    return itemView.getContext().getString(R.string.play_mode_loop);
                default:
                    return itemView.getContext().getString(R.string.play_mode_sequence);
            }
        }

        private String getRepeatDaysText(int repeatDays) {
            if (repeatDays == TaskEntity.EVERYDAY) {
                return "每天";
            }

            StringBuilder sb = new StringBuilder();
            String[] dayNames = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
            int[] dayFlags = {
                    TaskEntity.MONDAY, TaskEntity.TUESDAY, TaskEntity.WEDNESDAY,
                    TaskEntity.THURSDAY, TaskEntity.FRIDAY, TaskEntity.SATURDAY, TaskEntity.SUNDAY
            };

            for (int i = 0; i < dayFlags.length; i++) {
                if ((repeatDays & dayFlags[i]) != 0) {
                    if (sb.length() > 0) {
                        sb.append(" ");
                    }
                    sb.append(dayNames[i]);
                }
            }

            return sb.length() > 0 ? sb.toString() : "仅一次";
        }
    }
}
