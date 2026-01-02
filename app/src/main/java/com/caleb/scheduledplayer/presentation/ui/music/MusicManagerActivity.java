package com.caleb.scheduledplayer.presentation.ui.music;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.caleb.scheduledplayer.R;
import com.caleb.scheduledplayer.databinding.ActivityMusicManagerBinding;
import com.caleb.scheduledplayer.presentation.ui.main.MainActivity;
import com.caleb.scheduledplayer.util.MusicScanner;
import com.caleb.scheduledplayer.util.MusicScanner.Playlist;
import com.google.android.material.navigation.NavigationView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 音乐管理页面
 * 显示歌单列表，支持扫描本地音乐
 */
public class MusicManagerActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {

    private ActivityMusicManagerBinding binding;
    private ActionBarDrawerToggle drawerToggle;

    private List<Playlist> playlists = new ArrayList<>();
    private PlaylistAdapter adapter;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 权限请求
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    scanMusic();
                } else {
                    Toast.makeText(this, "需要存储权限才能扫描音乐", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMusicManagerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setupToolbar();
        setupDrawer();
        setupRecyclerView();
        setupClickListeners();

        // 自动扫描
        checkPermissionAndScan();
    }

    private void setupToolbar() {
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }

    private void setupDrawer() {
        drawerToggle = new ActionBarDrawerToggle(
                this,
                binding.drawerLayout,
                binding.toolbar,
                R.string.app_name,
                R.string.app_name
        );
        binding.drawerLayout.addDrawerListener(drawerToggle);
        drawerToggle.syncState();

        binding.navigationView.setNavigationItemSelectedListener(this);
        binding.navigationView.setCheckedItem(R.id.nav_music);
    }

    private void setupRecyclerView() {
        adapter = new PlaylistAdapter();
        binding.recyclerViewPlaylists.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerViewPlaylists.setAdapter(adapter);
    }

    private void setupClickListeners() {
        binding.buttonScan.setOnClickListener(v -> checkPermissionAndScan());
    }

    private void checkPermissionAndScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                scanMusic();
            } else {
                requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                scanMusic();
            } else {
                requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
    }

    private void scanMusic() {
        binding.progressScanning.setVisibility(View.VISIBLE);
        binding.buttonScan.setEnabled(false);
        binding.textScanStatus.setText("正在扫描...");

        executor.execute(() -> {
            List<Playlist> result = MusicScanner.scanMusic(this);
            int totalSongs = 0;
            for (Playlist p : result) {
                totalSongs += p.getFileCount();
            }
            final int finalTotalSongs = totalSongs;

            mainHandler.post(() -> {
                binding.progressScanning.setVisibility(View.GONE);
                binding.buttonScan.setEnabled(true);

                playlists = result;
                adapter.notifyDataSetChanged();

                if (playlists.isEmpty()) {
                    binding.layoutEmpty.setVisibility(View.VISIBLE);
                    binding.recyclerViewPlaylists.setVisibility(View.GONE);
                    binding.textScanStatus.setText("未找到音乐文件");
                } else {
                    binding.layoutEmpty.setVisibility(View.GONE);
                    binding.recyclerViewPlaylists.setVisibility(View.VISIBLE);
                    binding.textScanStatus.setText("共 " + playlists.size() + " 个歌单，" + finalTotalSongs + " 首歌曲");
                }
            });
        });
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        
        if (id == R.id.nav_tasks) {
            // 跳转到任务管理
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        } else if (id == R.id.nav_music) {
            // 已在音乐管理页面
        }

        binding.drawerLayout.closeDrawer(GravityCompat.START);
        return true;
    }

    @Override
    public void onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        binding = null;
    }

    /**
     * 歌单列表适配器
     */
    private class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_playlist_manager, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Playlist playlist = playlists.get(position);

            holder.textPlaylistName.setText(playlist.getName());
            holder.textPlaylistPath.setText(MusicScanner.simplifyPath(playlist.getPath()));
            holder.textFileCount.setText(playlist.getFileCount() + " 首");

            holder.itemView.setOnClickListener(v -> {
                // 打开歌单详情
                Intent intent = new Intent(MusicManagerActivity.this, PlaylistDetailActivity.class);
                intent.putExtra(PlaylistDetailActivity.EXTRA_PLAYLIST_PATH, playlist.getPath());
                intent.putExtra(PlaylistDetailActivity.EXTRA_PLAYLIST_NAME, playlist.getName());
                startActivity(intent);
            });
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
}
