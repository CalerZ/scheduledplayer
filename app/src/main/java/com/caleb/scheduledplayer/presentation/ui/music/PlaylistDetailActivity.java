package com.caleb.scheduledplayer.presentation.ui.music;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.caleb.scheduledplayer.R;
import com.caleb.scheduledplayer.databinding.ActivityPlaylistDetailBinding;
import com.caleb.scheduledplayer.util.MusicScanner;
import com.caleb.scheduledplayer.util.MusicScanner.MusicFile;
import com.caleb.scheduledplayer.util.MusicScanner.Playlist;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 歌单详情页面
 * 显示歌单中的所有音乐
 */
public class PlaylistDetailActivity extends AppCompatActivity {

    public static final String EXTRA_PLAYLIST_PATH = "playlist_path";
    public static final String EXTRA_PLAYLIST_NAME = "playlist_name";

    private ActivityPlaylistDetailBinding binding;

    private String playlistPath;
    private String playlistName;
    private List<MusicFile> musicFiles = new ArrayList<>();
    private MusicAdapter adapter;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPlaylistDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        playlistPath = getIntent().getStringExtra(EXTRA_PLAYLIST_PATH);
        playlistName = getIntent().getStringExtra(EXTRA_PLAYLIST_NAME);

        setupToolbar();
        setupRecyclerView();
        loadMusic();
    }

    private void setupToolbar() {
        setSupportActionBar(binding.toolbar);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(playlistName != null ? playlistName : "歌单详情");
        }

        binding.textPath.setText(MusicScanner.simplifyPath(playlistPath));
    }

    private void setupRecyclerView() {
        adapter = new MusicAdapter();
        binding.recyclerViewMusic.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerViewMusic.setAdapter(adapter);
    }

    private void loadMusic() {
        if (playlistPath == null) {
            binding.layoutEmpty.setVisibility(View.VISIBLE);
            return;
        }

        binding.progressBar.setVisibility(View.VISIBLE);

        executor.execute(() -> {
            // 扫描所有音乐，然后找到对应歌单
            List<Playlist> playlists = MusicScanner.scanMusic(this);
            List<MusicFile> result = new ArrayList<>();

            for (Playlist playlist : playlists) {
                if (playlistPath.equals(playlist.getPath())) {
                    result.addAll(playlist.getMusicFiles());
                    break;
                }
            }

            mainHandler.post(() -> {
                binding.progressBar.setVisibility(View.GONE);
                musicFiles = result;
                adapter.notifyDataSetChanged();

                binding.textSongCount.setText(musicFiles.size() + " 首歌曲");

                if (musicFiles.isEmpty()) {
                    binding.layoutEmpty.setVisibility(View.VISIBLE);
                    binding.recyclerViewMusic.setVisibility(View.GONE);
                } else {
                    binding.layoutEmpty.setVisibility(View.GONE);
                    binding.recyclerViewMusic.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        binding = null;
    }

    /**
     * 音乐列表适配器
     */
    private class MusicAdapter extends RecyclerView.Adapter<MusicAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_music, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            MusicFile music = musicFiles.get(position);

            holder.textMusicTitle.setText(music.getFileName());
            
            String artist = music.getArtist();
            if (artist == null || artist.isEmpty() || artist.equals("<unknown>")) {
                artist = "未知艺术家";
            }
            holder.textMusicInfo.setText(artist);
            holder.textDuration.setText(music.getFormattedDuration());
        }

        @Override
        public int getItemCount() {
            return musicFiles.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView textMusicTitle;
            TextView textMusicInfo;
            TextView textDuration;

            ViewHolder(View itemView) {
                super(itemView);
                textMusicTitle = itemView.findViewById(R.id.textMusicTitle);
                textMusicInfo = itemView.findViewById(R.id.textMusicInfo);
                textDuration = itemView.findViewById(R.id.textDuration);
            }
        }
    }
}
