package com.caleb.scheduledplayer.presentation.ui.widget;

import android.app.Dialog;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.caleb.scheduledplayer.R;
import com.caleb.scheduledplayer.util.MusicScanner;
import com.caleb.scheduledplayer.util.MusicScanner.MusicFile;
import com.caleb.scheduledplayer.util.MusicScanner.Playlist;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 歌单选择对话框
 * 支持扫描本地音乐，按目录显示歌单，选择歌单或其中的音乐
 */
public class PlaylistPickerDialog extends Dialog {

    public interface OnMusicSelectedListener {
        void onMusicSelected(List<Uri> selectedUris);
    }

    private final Context context;
    private final OnMusicSelectedListener listener;

    private RecyclerView recyclerView;
    private View progressBar;
    private View layoutEmpty;
    private TextView textTitle;
    private ImageButton buttonBack;
    private View layoutBottomBar;
    private CheckBox checkboxSelectAll;
    private TextView textSelectedCount;
    private MaterialButton buttonConfirm;

    private List<Playlist> playlists = new ArrayList<>();
    private Playlist currentPlaylist = null;
    private final Set<Integer> selectedMusicIndices = new HashSet<>();

    private PlaylistAdapter playlistAdapter;
    private MusicAdapter musicAdapter;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public PlaylistPickerDialog(@NonNull Context context, OnMusicSelectedListener listener) {
        super(context, R.style.Theme_ScheduledPlayer_Dialog);
        this.context = context;
        this.listener = listener;

        initView();
        loadPlaylists();
    }

    private void initView() {
        setContentView(R.layout.dialog_playlist_picker);

        // 设置对话框大小
        Window window = getWindow();
        if (window != null) {
            window.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    (int) (context.getResources().getDisplayMetrics().heightPixels * 0.7)
            );
        }

        recyclerView = findViewById(R.id.recyclerView);
        progressBar = findViewById(R.id.progressBar);
        layoutEmpty = findViewById(R.id.layoutEmpty);
        textTitle = findViewById(R.id.textTitle);
        buttonBack = findViewById(R.id.buttonBack);
        layoutBottomBar = findViewById(R.id.layoutBottomBar);
        checkboxSelectAll = findViewById(R.id.checkboxSelectAll);
        textSelectedCount = findViewById(R.id.textSelectedCount);
        buttonConfirm = findViewById(R.id.buttonConfirm);

        recyclerView.setLayoutManager(new LinearLayoutManager(context));

        // 返回按钮
        buttonBack.setOnClickListener(v -> {
            if (currentPlaylist != null) {
                showPlaylists();
            }
        });

        // 全选
        checkboxSelectAll.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (currentPlaylist != null && musicAdapter != null) {
                if (isChecked) {
                    // 全选
                    for (int i = 0; i < currentPlaylist.getMusicFiles().size(); i++) {
                        selectedMusicIndices.add(i);
                    }
                } else {
                    // 取消全选
                    selectedMusicIndices.clear();
                }
                musicAdapter.notifyDataSetChanged();
                updateSelectedCount();
            }
        });

        // 确定按钮
        buttonConfirm.setOnClickListener(v -> {
            if (currentPlaylist != null && !selectedMusicIndices.isEmpty()) {
                List<Uri> selectedUris = new ArrayList<>();
                for (int index : selectedMusicIndices) {
                    if (index < currentPlaylist.getMusicFiles().size()) {
                        selectedUris.add(currentPlaylist.getMusicFiles().get(index).getUri());
                    }
                }
                if (listener != null) {
                    listener.onMusicSelected(selectedUris);
                }
                dismiss();
            }
        });

        // 初始化适配器
        playlistAdapter = new PlaylistAdapter();
        musicAdapter = new MusicAdapter();
    }

    private void loadPlaylists() {
        progressBar.setVisibility(View.VISIBLE);
        layoutEmpty.setVisibility(View.GONE);

        executor.execute(() -> {
            List<Playlist> result = MusicScanner.scanMusic(context);
            mainHandler.post(() -> {
                progressBar.setVisibility(View.GONE);
                playlists = result;
                if (playlists.isEmpty()) {
                    layoutEmpty.setVisibility(View.VISIBLE);
                } else {
                    showPlaylists();
                }
            });
        });
    }

    private void showPlaylists() {
        currentPlaylist = null;
        selectedMusicIndices.clear();

        textTitle.setText("选择歌单");
        buttonBack.setVisibility(View.GONE);
        layoutBottomBar.setVisibility(View.GONE);

        recyclerView.setAdapter(playlistAdapter);
        playlistAdapter.notifyDataSetChanged();
    }

    private void showMusicList(Playlist playlist) {
        currentPlaylist = playlist;
        selectedMusicIndices.clear();

        textTitle.setText(playlist.getName());
        buttonBack.setVisibility(View.VISIBLE);
        layoutBottomBar.setVisibility(View.VISIBLE);

        checkboxSelectAll.setChecked(false);
        updateSelectedCount();

        recyclerView.setAdapter(musicAdapter);
        musicAdapter.notifyDataSetChanged();
    }

    private void updateSelectedCount() {
        int count = selectedMusicIndices.size();
        if (count == 0) {
            textSelectedCount.setText("未选择");
            buttonConfirm.setEnabled(false);
        } else {
            textSelectedCount.setText("已选 " + count + " 首");
            buttonConfirm.setEnabled(true);
        }

        // 更新全选状态
        if (currentPlaylist != null) {
            boolean allSelected = selectedMusicIndices.size() == currentPlaylist.getMusicFiles().size();
            checkboxSelectAll.setOnCheckedChangeListener(null);
            checkboxSelectAll.setChecked(allSelected);
            checkboxSelectAll.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    for (int i = 0; i < currentPlaylist.getMusicFiles().size(); i++) {
                        selectedMusicIndices.add(i);
                    }
                } else {
                    selectedMusicIndices.clear();
                }
                musicAdapter.notifyDataSetChanged();
                updateSelectedCount();
            });
        }
    }

    @Override
    public void dismiss() {
        super.dismiss();
        executor.shutdown();
    }

    /**
     * 歌单列表适配器
     */
    private class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_playlist, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Playlist playlist = playlists.get(position);

            holder.textPlaylistName.setText(playlist.getName());
            holder.textPlaylistPath.setText(MusicScanner.simplifyPath(playlist.getPath()));
            holder.textFileCount.setText(playlist.getFileCount() + " 首歌曲");

            holder.itemView.setOnClickListener(v -> showMusicList(playlist));
        }

        @Override
        public int getItemCount() {
            return playlists.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView textPlaylistName;
            TextView textPlaylistPath;
            TextView textFileCount;

            ViewHolder(View itemView) {
                super(itemView);
                textPlaylistName = itemView.findViewById(R.id.textPlaylistName);
                textPlaylistPath = itemView.findViewById(R.id.textPlaylistPath);
                textFileCount = itemView.findViewById(R.id.textFileCount);
            }
        }
    }

    /**
     * 音乐列表适配器
     */
    private class MusicAdapter extends RecyclerView.Adapter<MusicAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_music_selectable, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            if (currentPlaylist == null) return;

            MusicFile music = currentPlaylist.getMusicFiles().get(position);
            boolean isSelected = selectedMusicIndices.contains(position);

            holder.textMusicTitle.setText(music.getFileName());
            
            String info = music.getFormattedDuration();
            if (music.getArtist() != null && !music.getArtist().isEmpty() 
                    && !music.getArtist().equals("<unknown>")) {
                info = music.getArtist() + " · " + info;
            }
            holder.textMusicInfo.setText(info);

            holder.checkbox.setOnCheckedChangeListener(null);
            holder.checkbox.setChecked(isSelected);

            View.OnClickListener clickListener = v -> {
                int pos = holder.getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;

                if (selectedMusicIndices.contains(pos)) {
                    selectedMusicIndices.remove(pos);
                } else {
                    selectedMusicIndices.add(pos);
                }
                notifyItemChanged(pos);
                updateSelectedCount();
            };

            holder.checkbox.setOnClickListener(clickListener);
            holder.itemView.setOnClickListener(clickListener);
        }

        @Override
        public int getItemCount() {
            return currentPlaylist != null ? currentPlaylist.getMusicFiles().size() : 0;
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            CheckBox checkbox;
            TextView textMusicTitle;
            TextView textMusicInfo;

            ViewHolder(View itemView) {
                super(itemView);
                checkbox = itemView.findViewById(R.id.checkbox);
                textMusicTitle = itemView.findViewById(R.id.textMusicTitle);
                textMusicInfo = itemView.findViewById(R.id.textMusicInfo);
            }
        }
    }

    /**
     * Builder 模式
     */
    public static class Builder {
        private final Context context;
        private OnMusicSelectedListener listener;

        public Builder(Context context) {
            this.context = context;
        }

        public Builder setOnMusicSelectedListener(OnMusicSelectedListener listener) {
            this.listener = listener;
            return this;
        }

        public PlaylistPickerDialog build() {
            return new PlaylistPickerDialog(context, listener);
        }

        public void show() {
            build().show();
        }
    }
}
