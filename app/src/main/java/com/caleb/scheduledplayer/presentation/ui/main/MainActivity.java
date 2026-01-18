package com.caleb.scheduledplayer.presentation.ui.main;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.caleb.scheduledplayer.R;
import com.caleb.scheduledplayer.data.entity.TaskEntity;
import com.caleb.scheduledplayer.databinding.ActivityMainBinding;
import com.caleb.scheduledplayer.presentation.ui.log.LogViewerActivity;
import com.caleb.scheduledplayer.presentation.ui.music.MusicManagerActivity;
import com.caleb.scheduledplayer.presentation.ui.settings.SettingsActivity;
import com.caleb.scheduledplayer.presentation.ui.task.TaskEditActivity;
import com.caleb.scheduledplayer.presentation.viewmodel.MainViewModel;
import com.caleb.scheduledplayer.service.player.AudioPlaybackService;
import com.caleb.scheduledplayer.util.HuaweiDeviceHelper;
import com.caleb.scheduledplayer.util.PermissionHelper;
import com.google.android.material.navigation.NavigationView;

/**
 * 主界面 - 任务列表
 */
public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {

    private ActivityMainBinding binding;
    private MainViewModel viewModel;
    private TaskAdapter taskAdapter;
    private ActionBarDrawerToggle drawerToggle;
    
    // 播放服务相关
    private AudioPlaybackService playbackService;
    private boolean serviceBound = false;
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private Runnable progressRunnable;
    
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            AudioPlaybackService.LocalBinder binder = (AudioPlaybackService.LocalBinder) service;
            playbackService = binder.getService();
            serviceBound = true;
            
            playbackService.setPlaybackCallback(new AudioPlaybackService.PlaybackCallback() {
                @Override
                public void onTaskStarted(long taskId) {
                    runOnUiThread(() -> {
                        updatePlaybackState();
                        startProgressUpdates();
                    });
                }

                @Override
                public void onTaskStopped(long taskId) {
                    runOnUiThread(() -> {
                        updatePlaybackState();
                        progressHandler.removeCallbacks(progressRunnable);
                    });
                }

                @Override
                public void onPlaybackStateChanged(AudioPlaybackService.PlaybackState state) {
                    // 兼容旧接口，但优先使用 onAllPlaybackStatesChanged
                }
                
                @Override
                public void onAllPlaybackStatesChanged(java.util.Map<Long, AudioPlaybackService.PlaybackState> states) {
                    runOnUiThread(() -> taskAdapter.updateAllPlaybackStates(states));
                }
            });
            
            updatePlaybackState();
            startProgressUpdates();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            playbackService = null;
            serviceBound = false;
            taskAdapter.updateAllPlaybackStates(null);
        }
    };

    // 权限请求
    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allGranted = true;
                for (Boolean granted : result.values()) {
                    if (!granted) {
                        allGranted = false;
                        break;
                    }
                }
                if (!allGranted) {
                    Toast.makeText(this, R.string.permission_storage_message, Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setupToolbar();
        setupDrawer();
        setupRecyclerView();
        setupViewModel();
        setupClickListeners();
        setupProgressUpdater();
        checkPermissions();
        checkHuaweiPermissions();
        // 任务调度已移至 ScheduledPlayerApp.onCreate() 中，只在应用启动时执行一次
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
        binding.navigationView.setCheckedItem(R.id.nav_tasks);
    }

    private void setupRecyclerView() {
        taskAdapter = new TaskAdapter(
                // 点击任务
                task -> {
                    Intent intent = new Intent(this, TaskEditActivity.class);
                    intent.putExtra(TaskEditActivity.EXTRA_TASK_ID, task.getId());
                    startActivity(intent);
                },
                // 切换启用状态
                (task, enabled) -> viewModel.updateTaskEnabled(task.getId(), enabled),
                // 长按任务
                this::showDeleteConfirmDialog
        );
        
        // 设置播放/暂停点击监听
        taskAdapter.setPlayPauseClickListener((taskId, isPaused) -> {
            if (serviceBound && playbackService != null) {
                if (isPaused) {
                    playbackService.resumePlayback(taskId);
                } else {
                    playbackService.pausePlayback(taskId);
                }
            }
        });

        binding.recyclerViewTasks.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerViewTasks.setAdapter(taskAdapter);
    }

    private void setupViewModel() {
        viewModel = new ViewModelProvider(this).get(MainViewModel.class);

        // 观察任务列表
        viewModel.getTasks().observe(this, tasks -> {
            // 提交列表副本，避免 DiffUtil 比较问题导致顺序变化
            taskAdapter.submitList(tasks != null ? new java.util.ArrayList<>(tasks) : null);
            
            // 显示/隐藏空状态
            if (tasks == null || tasks.isEmpty()) {
                binding.layoutEmpty.setVisibility(View.VISIBLE);
                binding.recyclerViewTasks.setVisibility(View.GONE);
            } else {
                binding.layoutEmpty.setVisibility(View.GONE);
                binding.recyclerViewTasks.setVisibility(View.VISIBLE);
            }
        });
    }

    private void setupClickListeners() {
        // 添加任务按钮
        binding.fabAddTask.setOnClickListener(v -> {
            Intent intent = new Intent(this, TaskEditActivity.class);
            startActivity(intent);
        });
    }
    
    private void setupProgressUpdater() {
        progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (serviceBound && playbackService != null) {
                    // 使用多任务播放状态
                    java.util.Map<Long, AudioPlaybackService.PlaybackState> states = playbackService.getAllPlaybackStates();
                    taskAdapter.updateAllPlaybackStates(states);
                    
                    // 只要服务绑定且有播放任务，就持续更新
                    int playingCount = playbackService.getPlayingTaskCount();
                    if (playingCount > 0) {
                        progressHandler.postDelayed(this, 500);
                    }
                }
            }
        };
    }
    
    private void updatePlaybackState() {
        if (!serviceBound || playbackService == null) {
            taskAdapter.updateAllPlaybackStates(null);
            return;
        }
        
        // 使用多任务播放状态
        java.util.Map<Long, AudioPlaybackService.PlaybackState> states = playbackService.getAllPlaybackStates();
        taskAdapter.updateAllPlaybackStates(states);
    }
    
    private void startProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable);
        progressHandler.post(progressRunnable);
    }
    
    private void bindPlaybackService() {
        Intent intent = new Intent(this, AudioPlaybackService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }
    
    private void unbindPlaybackService() {
        if (serviceBound) {
            if (playbackService != null) {
                playbackService.setPlaybackCallback(null);
            }
            unbindService(serviceConnection);
            serviceBound = false;
        }
    }

    private void checkPermissions() {
        String[] permissions;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            permissions = new String[]{
                    Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS
            };
        } else {
            // Android 12 及以下
            permissions = new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE
            };
        }

        boolean needRequest = false;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                needRequest = true;
                break;
            }
        }

        if (needRequest) {
            permissionLauncher.launch(permissions);
        }
    }

    private void checkHuaweiPermissions() {
        // 检查是否为华为设备
        if (HuaweiDeviceHelper.isHuaweiDevice()) {
            // 首次启动时显示权限引导
            if (!PermissionHelper.hasShownPermissionGuide(this)) {
                PermissionHelper.showPermissionGuideDialog(this, () -> {
                    PermissionHelper.setPermissionGuideShown(this);
                });
            }
        }
    }

    private void showDeleteConfirmDialog(TaskEntity task) {
        new AlertDialog.Builder(this)
                .setTitle("删除任务")
                .setMessage("确定要删除任务\"" + task.getName() + "\"吗？")
                .setPositiveButton("删除", (dialog, which) -> {
                    viewModel.deleteTask(task);
                    Toast.makeText(this, "任务已删除", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        
        if (id == R.id.nav_tasks) {
            // 已在任务管理页面
        } else if (id == R.id.nav_music) {
            // 跳转到音乐管理
            Intent intent = new Intent(this, MusicManagerActivity.class);
            startActivity(intent);
        } else if (id == R.id.nav_logs) {
            // 跳转到日志查看
            Intent intent = new Intent(this, LogViewerActivity.class);
            startActivity(intent);
        } else if (id == R.id.nav_settings) {
            // 跳转到设置页面
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
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
    protected void onResume() {
        super.onResume();
        // 确保选中任务管理
        binding.navigationView.setCheckedItem(R.id.nav_tasks);
        // 绑定播放服务
        bindPlaybackService();
        // 如果服务已绑定，立即启动进度更新
        if (serviceBound && playbackService != null) {
            updatePlaybackState();
            startProgressUpdates();
        }
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        progressHandler.removeCallbacks(progressRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindPlaybackService();
        binding = null;
    }
}
