package com.caleb.scheduledplayer.presentation.adapter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.caleb.scheduledplayer.R;
import com.caleb.scheduledplayer.util.AudioFileValidator;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 音频文件列表适配器
 * 支持拖拽排序、显示序号和时长
 * 优化：异步加载时长 + 缓存机制
 */
public class AudioFileAdapter extends RecyclerView.Adapter<AudioFileAdapter.ViewHolder> {

    private final Context context;
    private final List<String> audioPaths;
    private OnItemRemovedListener onItemRemovedListener;
    private OnOrderChangedListener onOrderChangedListener;
    private ItemTouchHelper itemTouchHelper;
    
    // 时长缓存（path -> duration string）
    private final Map<String, String> durationCache = new ConcurrentHashMap<>();
    // 文件名缓存（path -> filename）
    private final Map<String, String> fileNameCache = new ConcurrentHashMap<>();
    // 后台线程池
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    // 主线程 Handler
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface OnItemRemovedListener {
        void onItemRemoved(int position);
    }

    public interface OnOrderChangedListener {
        void onOrderChanged();
    }

    public AudioFileAdapter(Context context, List<String> audioPaths) {
        this.context = context;
        this.audioPaths = audioPaths;
    }

    public void setOnItemRemovedListener(OnItemRemovedListener listener) {
        this.onItemRemovedListener = listener;
    }

    public void setOnOrderChangedListener(OnOrderChangedListener listener) {
        this.onOrderChangedListener = listener;
    }

    public void setItemTouchHelper(ItemTouchHelper itemTouchHelper) {
        this.itemTouchHelper = itemTouchHelper;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_audio_file, parent, false);
        return new ViewHolder(view);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String path = audioPaths.get(position);

        // 序号（从01开始）
        holder.textIndex.setText(String.format(Locale.getDefault(), "%02d", position + 1));
        
        // 记录当前绑定的路径，用于防止 ViewHolder 复用时显示错误数据
        holder.currentPath = path;

        // 异步获取文件名（带缓存）
        loadFileNameAsync(path, holder);

        // 异步获取时长（带缓存）
        loadDurationAsync(path, holder);

        // 删除按钮
        holder.buttonRemove.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && onItemRemovedListener != null) {
                onItemRemovedListener.onItemRemoved(pos);
            }
        });

        // 拖拽手柄
        holder.dragHandle.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && itemTouchHelper != null) {
                itemTouchHelper.startDrag(holder);
            }
            return false;
        });
    }
    
    /**
     * 异步加载文件名（带缓存）
     */
    private void loadFileNameAsync(String path, ViewHolder holder) {
        // 先检查缓存
        String cachedName = fileNameCache.get(path);
        if (cachedName != null) {
            holder.textFileName.setText(cachedName);
            return;
        }
        
        // 显示加载中（使用 URI 最后一段作为临时显示）
        String tempName = getQuickFileName(path);
        holder.textFileName.setText(tempName);
        
        // 异步获取完整文件名
        executor.execute(() -> {
            String fileName = AudioFileValidator.getFileName(context, path);
            fileNameCache.put(path, fileName);
            
            // 回到主线程更新 UI
            mainHandler.post(() -> {
                if (path.equals(holder.currentPath)) {
                    holder.textFileName.setText(fileName);
                }
            });
        });
    }
    
    /**
     * 快速获取文件名（不查询 ContentResolver，用于临时显示）
     */
    private String getQuickFileName(String path) {
        if (path == null || path.isEmpty()) {
            return "加载中...";
        }
        try {
            Uri uri = Uri.parse(path);
            String lastSegment = uri.getLastPathSegment();
            if (lastSegment != null) {
                // URL 解码
                return Uri.decode(lastSegment);
            }
        } catch (Exception e) {
            // ignore
        }
        return "加载中...";
    }
    
    /**
     * 异步加载时长（带缓存）
     */
    private void loadDurationAsync(String path, ViewHolder holder) {
        // 先检查缓存
        String cachedDuration = durationCache.get(path);
        if (cachedDuration != null) {
            holder.textDuration.setText(cachedDuration);
            return;
        }
        
        // 显示加载中
        holder.textDuration.setText("...");
        
        // 异步获取
        executor.execute(() -> {
            String duration = getAudioDuration(path);
            durationCache.put(path, duration);
            
            // 回到主线程更新 UI
            mainHandler.post(() -> {
                // 检查 ViewHolder 是否仍然绑定到同一路径
                if (path.equals(holder.currentPath)) {
                    holder.textDuration.setText(duration);
                }
            });
        });
    }

    @Override
    public int getItemCount() {
        return audioPaths.size();
    }

    /**
     * 获取音频时长
     */
    private String getAudioDuration(String path) {
        try {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            Uri uri = Uri.parse(path);
            retriever.setDataSource(context, uri);
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            retriever.release();

            if (durationStr != null) {
                long durationMs = Long.parseLong(durationStr);
                return formatDuration(durationMs);
            }
        } catch (Exception e) {
            // 无法获取时长
        }
        return "--:--";
    }

    /**
     * 格式化时长
     */
    private String formatDuration(long durationMs) {
        long seconds = durationMs / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;

        if (minutes >= 60) {
            long hours = minutes / 60;
            minutes = minutes % 60;
            return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }

    /**
     * 移动项目（拖拽排序）
     */
    public void moveItem(int fromPosition, int toPosition) {
        if (fromPosition < toPosition) {
            for (int i = fromPosition; i < toPosition; i++) {
                Collections.swap(audioPaths, i, i + 1);
            }
        } else {
            for (int i = fromPosition; i > toPosition; i--) {
                Collections.swap(audioPaths, i, i - 1);
            }
        }
        notifyItemMoved(fromPosition, toPosition);
    }

    /**
     * 拖拽完成后更新序号
     */
    public void onDragCompleted() {
        notifyItemRangeChanged(0, audioPaths.size());
        if (onOrderChangedListener != null) {
            onOrderChangedListener.onOrderChanged();
        }
    }
    
    /**
     * 清理资源（Activity 销毁时调用）
     */
    public void release() {
        executor.shutdownNow();
        durationCache.clear();
        fileNameCache.clear();
    }
    
    /**
     * 清除缓存
     */
    public void clearCache() {
        durationCache.clear();
        fileNameCache.clear();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView dragHandle;
        TextView textIndex;
        TextView textFileName;
        TextView textDuration;
        ImageButton buttonRemove;
        String currentPath; // 用于防止 ViewHolder 复用时显示错误数据

        ViewHolder(View itemView) {
            super(itemView);
            dragHandle = itemView.findViewById(R.id.dragHandle);
            textIndex = itemView.findViewById(R.id.textIndex);
            textFileName = itemView.findViewById(R.id.textFileName);
            textDuration = itemView.findViewById(R.id.textDuration);
            buttonRemove = itemView.findViewById(R.id.buttonRemove);
        }
    }

    /**
     * 拖拽排序回调
     */
    public static class DragCallback extends ItemTouchHelper.Callback {

        private final AudioFileAdapter adapter;

        public DragCallback(AudioFileAdapter adapter) {
            this.adapter = adapter;
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return false; // 使用拖拽手柄，禁用长按拖拽
        }

        @Override
        public boolean isItemViewSwipeEnabled() {
            return false; // 禁用滑动删除
        }

        @Override
        public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            int dragFlags = ItemTouchHelper.UP | ItemTouchHelper.DOWN;
            return makeMovementFlags(dragFlags, 0);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
            adapter.moveItem(viewHolder.getAdapterPosition(), target.getAdapterPosition());
            return true;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            // 不处理滑动
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            // 拖拽完成后更新序号
            adapter.onDragCompleted();
        }
    }
}
