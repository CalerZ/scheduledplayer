package com.caleb.scheduledplayer.presentation.ui.task;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.caleb.scheduledplayer.R;
import com.caleb.scheduledplayer.data.converter.Converters;
import com.caleb.scheduledplayer.data.entity.TaskEntity;
import com.caleb.scheduledplayer.databinding.ActivityTaskEditBinding;
import com.caleb.scheduledplayer.presentation.ui.log.TaskLogActivity;
import com.caleb.scheduledplayer.presentation.ui.widget.PlaylistPickerDialog;
import com.caleb.scheduledplayer.presentation.ui.widget.RepeatDaysBottomSheet;
import com.caleb.scheduledplayer.presentation.ui.widget.WheelTimePickerDialog;
import com.caleb.scheduledplayer.presentation.viewmodel.TaskEditViewModel;
import com.caleb.scheduledplayer.service.scheduler.TaskSchedulerService;
import com.caleb.scheduledplayer.presentation.adapter.AudioFileAdapter;
import com.caleb.scheduledplayer.util.AudioFileValidator;
import com.caleb.scheduledplayer.util.BluetoothHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务编辑界面
 */
public class TaskEditActivity extends AppCompatActivity {

    public static final String EXTRA_TASK_ID = "task_id";

    private ActivityTaskEditBinding binding;
    private TaskEditViewModel viewModel;
    private long taskId = -1;

    // 选中的开始时间
    private int selectedStartHour = 7;
    private int selectedStartMinute = 0;
    
    // 选中的结束时间
    private int selectedEndHour = 8;
    private int selectedEndMinute = 0;
    
    // 播放模式 (0: 顺序, 1: 随机, 2: 循环)
    private int currentPlayMode = TaskEntity.PLAY_MODE_SEQUENCE;
    
    // 播放设备 (0: 扬声器, 1: 蓝牙)
    private int currentOutputDevice = TaskEntity.OUTPUT_DEVICE_DEFAULT;
    
    // 重复日期
    private int currentRepeatDays = 0;
    
    // 原始任务的创建时间和启用状态
    private long originalCreatedAt = 0;
    private boolean originalEnabled = true;
    private int originalVolume = 100;

    // 音频文件列表
    private final List<String> audioPaths = new ArrayList<>();
    private AudioFileAdapter audioAdapter;
    
    // 蓝牙辅助类
    private BluetoothHelper bluetoothHelper;

    // 权限请求
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    openAudioPicker();
                } else {
                    Toast.makeText(this, "需要存储权限才能选择音频文件", Toast.LENGTH_SHORT).show();
                }
            });

    // 歌单权限请求
    private final ActivityResultLauncher<String> requestPlaylistPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    openPlaylistPicker();
                } else {
                    Toast.makeText(this, "需要存储权限才能扫描音乐", Toast.LENGTH_SHORT).show();
                }
            });

    // 音频文件选择
    private final ActivityResultLauncher<Intent> audioPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    handleAudioSelection(result.getData());
                }
            });

    // 蓝牙权限请求
    private final ActivityResultLauncher<String> requestBluetoothPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                updateBluetoothStatus();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityTaskEditBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        taskId = getIntent().getLongExtra(EXTRA_TASK_ID, -1);

        bluetoothHelper = new BluetoothHelper(this);

        setupToolbar();
        setupViewModel();
        setupAudioList();
        setupClickListeners();
        setupBluetoothUI();
        setupDefaultValues();
    }

    private void setupToolbar() {
        setSupportActionBar(binding.toolbar);
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        
        // 设置菜单点击监听器（MaterialToolbar需要单独设置）
        binding.toolbar.setOnMenuItemClickListener(item -> onOptionsItemSelected(item));

        if (taskId > 0) {
            binding.toolbar.setTitle("编辑任务");
        } else {
            binding.toolbar.setTitle("添加任务");
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_task_edit, menu);
        // 仅编辑模式显示日志菜单
        MenuItem logItem = menu.findItem(R.id.action_view_logs);
        if (logItem != null) {
            logItem.setVisible(taskId > 0);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_save) {
            saveTask();
            return true;
        } else if (id == R.id.action_view_logs) {
            openTaskLogs();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
    
    /**
     * 保存任务
     */
    private void saveTask() {
        // 验证任务名称
        String name = binding.editTaskName.getText().toString().trim();
        if (name.isEmpty()) {
            binding.layoutTaskName.setError("请输入任务名称");
            return;
        }
        binding.layoutTaskName.setError(null);
        
        // 构建任务
        TaskEntity task = buildTask();
        if (task == null) {
            return;
        }
        
        // 保存任务
        viewModel.saveTask(task, savedId -> {
            taskId = savedId;
            task.setId(savedId);
            
            // 调度任务
            TaskSchedulerService scheduler = new TaskSchedulerService(this);
            scheduler.scheduleTask(task);
            
            Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show();
            finish();
        });
    }
    
    /**
     * 构建任务对象
     */
    private TaskEntity buildTask() {
        String name = binding.editTaskName.getText().toString().trim();
        
        boolean allDayPlay = binding.switchAllDayPlay.isChecked();
        
        TaskEntity task = new TaskEntity();
        if (taskId > 0) {
            task.setId(taskId);
        }
        task.setName(name);
        task.setStartTime(String.format("%02d:%02d", selectedStartHour, selectedStartMinute));
        task.setEndTime(String.format("%02d:%02d", selectedEndHour, selectedEndMinute));
        task.setPlayMode(currentPlayMode);
        task.setRepeatDays(currentRepeatDays);
        task.setEnabled(originalEnabled);
        task.setVolume(originalVolume);
        task.setAudioPaths(Converters.toAudioPathsJson(audioPaths));
        task.setOutputDevice(currentOutputDevice);
        task.setAllDayPlay(allDayPlay);
        task.setCreatedAt(originalCreatedAt > 0 ? originalCreatedAt : System.currentTimeMillis());
        task.setUpdatedAt(System.currentTimeMillis());
        
        return task;
    }

    private void setupViewModel() {
        viewModel = new ViewModelProvider(this).get(TaskEditViewModel.class);

        // 加载现有任务
        if (taskId > 0) {
            viewModel.loadTask(taskId);
            viewModel.getTask().observe(this, this::populateTaskData);
        } else {
            // 新建任务
            originalCreatedAt = System.currentTimeMillis();
        }
    }

    private void setupAudioList() {
        audioAdapter = new AudioFileAdapter(this, audioPaths);
        audioAdapter.setOnItemRemovedListener(this::removeAudioFile);
        
        // 设置拖拽排序
        ItemTouchHelper.Callback callback = new AudioFileAdapter.DragCallback(audioAdapter);
        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(callback);
        itemTouchHelper.attachToRecyclerView(binding.recyclerViewAudio);
        audioAdapter.setItemTouchHelper(itemTouchHelper);
        
        binding.recyclerViewAudio.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerViewAudio.setAdapter(audioAdapter);
        
        updateMusicListVisibility();
    }

    private void setupClickListeners() {
        // 开始时间选择
        binding.editStartTime.setOnClickListener(v -> showStartTimePicker());
        
        // 结束时间选择
        binding.editEndTime.setOnClickListener(v -> showEndTimePicker());

        // 全天播放开关
        binding.switchAllDayPlay.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // 全天播放时隐藏时间范围选择
            binding.layoutTimeRange.setVisibility(isChecked ? View.GONE : View.VISIBLE);
        });

        // 选择音频按钮
        binding.buttonSelectAudio.setOnClickListener(v -> checkPermissionAndPickAudio());

        // 从歌单导入按钮
        binding.buttonImportFromPlaylist.setOnClickListener(v -> checkPermissionAndOpenPlaylistPicker());
        
        // 播放模式点击切换
        binding.layoutPlayMode.setOnClickListener(v -> togglePlayMode());
        
        // 播放设备点击切换
        binding.layoutOutputDevice.setOnClickListener(v -> toggleOutputDevice());
        
        // 重复日期点击选择
        binding.layoutRepeatDays.setOnClickListener(v -> showRepeatDaysPicker());
    }
    
    private void togglePlayMode() {
        currentPlayMode = (currentPlayMode + 1) % 3;
        updatePlayModeDisplay();
    }
    
    private void updatePlayModeDisplay() {
        switch (currentPlayMode) {
            case TaskEntity.PLAY_MODE_RANDOM:
                binding.imagePlayMode.setImageResource(R.drawable.ic_play_random);
                binding.textPlayMode.setText(R.string.play_mode_random);
                break;
            case TaskEntity.PLAY_MODE_LOOP:
                binding.imagePlayMode.setImageResource(R.drawable.ic_play_loop);
                binding.textPlayMode.setText(R.string.play_mode_loop);
                break;
            default:
                binding.imagePlayMode.setImageResource(R.drawable.ic_play_sequence);
                binding.textPlayMode.setText(R.string.play_mode_sequence);
        }
    }
    
    private void toggleOutputDevice() {
        currentOutputDevice = (currentOutputDevice + 1) % 2;
        updateOutputDeviceDisplay();
        
        // 切换到蓝牙时检查权限
        if (currentOutputDevice == TaskEntity.OUTPUT_DEVICE_BLUETOOTH) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !bluetoothHelper.hasBluetoothPermission()) {
                requestBluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT);
            } else {
                updateBluetoothStatus();
            }
        } else {
            binding.textBluetoothStatus.setVisibility(View.GONE);
        }
    }
    
    private void updateOutputDeviceDisplay() {
        if (currentOutputDevice == TaskEntity.OUTPUT_DEVICE_BLUETOOTH) {
            binding.imageOutputDevice.setImageResource(R.drawable.ic_bluetooth_speaker);
            binding.textOutputDevice.setText("蓝牙音箱");
        } else {
            binding.imageOutputDevice.setImageResource(R.drawable.ic_speaker);
            binding.textOutputDevice.setText("扬声器");
        }
    }
    
    private void showRepeatDaysPicker() {
        new RepeatDaysBottomSheet.Builder(this)
                .setInitialDays(currentRepeatDays)
                .setOnDaysSelectedListener(days -> {
                    currentRepeatDays = days;
                    updateRepeatDaysDisplay();
                })
                .show();
    }
    
    private void updateRepeatDaysDisplay() {
        binding.textRepeatDays.setText(RepeatDaysBottomSheet.formatRepeatDays(currentRepeatDays));
    }

    private void setupDefaultValues() {
        // 默认时间
        updateStartTimeDisplay();
        updateEndTimeDisplay();
    }

    private void setupBluetoothUI() {
        // 初始化蓝牙状态显示
        updateBluetoothStatus();
    }

    private void updateBluetoothStatus() {
        if (currentOutputDevice != TaskEntity.OUTPUT_DEVICE_BLUETOOTH) {
            binding.textBluetoothStatus.setVisibility(View.GONE);
            return;
        }

        binding.textBluetoothStatus.setVisibility(View.VISIBLE);
        
        if (!bluetoothHelper.hasBluetoothPermission()) {
            binding.textBluetoothStatus.setText("需要蓝牙权限");
            binding.textBluetoothStatus.setTextColor(getColor(R.color.error));
        } else if (!bluetoothHelper.isBluetoothEnabled()) {
            binding.textBluetoothStatus.setText("蓝牙未开启");
            binding.textBluetoothStatus.setTextColor(getColor(R.color.error));
        } else if (bluetoothHelper.isBluetoothAudioConnected()) {
            List<String> devices = bluetoothHelper.getConnectedBluetoothAudioDevices();
            String deviceNames = devices.isEmpty() ? "蓝牙音频设备" : String.join(", ", devices);
            binding.textBluetoothStatus.setText("已连接: " + deviceNames);
            binding.textBluetoothStatus.setTextColor(getColor(R.color.secondary));
        } else {
            binding.textBluetoothStatus.setText("未检测到蓝牙音频设备");
            binding.textBluetoothStatus.setTextColor(getColor(R.color.error));
        }
    }

    /**
     * 打开任务日志页面
     */
    private void openTaskLogs() {
        String taskName = binding.editTaskName.getText().toString().trim();
        Intent intent = new Intent(this, TaskLogActivity.class);
        intent.putExtra(TaskLogActivity.EXTRA_TASK_ID, taskId);
        intent.putExtra(TaskLogActivity.EXTRA_TASK_NAME, taskName);
        startActivity(intent);
    }

    private void checkPermissionAndPickAudio() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 使用 READ_MEDIA_AUDIO
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                openAudioPicker();
            } else {
                requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO);
            }
        } else {
            // Android 12 及以下使用 READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                openAudioPicker();
            } else {
                requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
    }

    private void checkPermissionAndOpenPlaylistPicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 使用 READ_MEDIA_AUDIO
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                openPlaylistPicker();
            } else {
                // 使用单独的权限请求，成功后打开歌单选择器
                requestPlaylistPermissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO);
            }
        } else {
            // Android 12 及以下使用 READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                openPlaylistPicker();
            } else {
                requestPlaylistPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
    }

    private void openPlaylistPicker() {
        new PlaylistPickerDialog.Builder(this)
                .setOnMusicSelectedListener(selectedUris -> {
                    // 添加选中的音乐
                    for (Uri uri : selectedUris) {
                        String uriString = uri.toString();
                        if (!audioPaths.contains(uriString)) {
                            audioPaths.add(uriString);
                        }
                    }
                    audioAdapter.notifyDataSetChanged();
                    updateMusicListVisibility();
                    Toast.makeText(this, "已添加 " + selectedUris.size() + " 首音乐", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void openAudioPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        // 支持多种音频格式，包括 m4a 和 m4s
        String[] mimeTypes = {
                "audio/*",           // 所有音频格式
                "video/mp4",         // m4s 可能被识别为 mp4
                "application/octet-stream"  // 通用二进制文件（用于 m4s）
        };
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        audioPickerLauncher.launch(intent);
    }

    private void handleAudioSelection(Intent data) {
        if (data.getClipData() != null) {
            // 多选
            int count = data.getClipData().getItemCount();
            for (int i = 0; i < count; i++) {
                Uri uri = data.getClipData().getItemAt(i).getUri();
                addAudioFile(uri);
            }
        } else if (data.getData() != null) {
            // 单选
            addAudioFile(data.getData());
        }
    }

    private void addAudioFile(Uri uri) {
        // 检查文件扩展名是否为支持的音频格式
        String fileName = AudioFileValidator.getFileName(this, uri.toString()).toLowerCase();
        if (!isAudioFile(fileName)) {
            Toast.makeText(this, "不支持的文件格式: " + fileName, Toast.LENGTH_SHORT).show();
            return;
        }

        // 获取持久化权限
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            // 某些设备可能不支持持久化权限
        }

        String uriString = uri.toString();
        if (!audioPaths.contains(uriString)) {
            audioPaths.add(uriString);
            audioAdapter.notifyItemInserted(audioPaths.size() - 1);
            updateMusicListVisibility();
        }
    }

    /**
     * 检查文件是否为支持的音频格式
     */
    private boolean isAudioFile(String fileName) {
        if (fileName == null) return false;
        String[] supportedExtensions = {
                ".mp3", ".wav", ".ogg", ".flac", ".aac",
                ".m4a", ".m4s",  // 新增支持
                ".wma", ".opus", ".amr", ".3gp", ".mp4"
        };
        for (String ext : supportedExtensions) {
            if (fileName.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private void removeAudioFile(int position) {
        if (position >= 0 && position < audioPaths.size()) {
            audioPaths.remove(position);
            audioAdapter.notifyItemRemoved(position);
            audioAdapter.notifyItemRangeChanged(position, audioPaths.size());
            updateMusicListVisibility();
        }
    }

    private void updateMusicListVisibility() {
        boolean hasMusic = !audioPaths.isEmpty();
        binding.layoutEmptyMusic.setVisibility(hasMusic ? View.GONE : View.VISIBLE);
        binding.recyclerViewAudio.setVisibility(hasMusic ? View.VISIBLE : View.GONE);
        binding.textMusicCount.setText("歌曲列表 (" + audioPaths.size() + ")");
    }

    private void showStartTimePicker() {
        new WheelTimePickerDialog.Builder(this)
                .setTitle("选择开始时间")
                .setHour(selectedStartHour)
                .setMinute(selectedStartMinute)
                .setOnTimeSelectedListener((hour, minute) -> {
                    selectedStartHour = hour;
                    selectedStartMinute = minute;
                    updateStartTimeDisplay();
                })
                .show();
    }
    
    private void showEndTimePicker() {
        new WheelTimePickerDialog.Builder(this)
                .setTitle("选择结束时间")
                .setHour(selectedEndHour)
                .setMinute(selectedEndMinute)
                .setOnTimeSelectedListener((hour, minute) -> {
                    selectedEndHour = hour;
                    selectedEndMinute = minute;
                    updateEndTimeDisplay();
                })
                .show();
    }

    private void updateStartTimeDisplay() {
        String timeText = String.format("%02d:%02d", selectedStartHour, selectedStartMinute);
        binding.editStartTime.setText(timeText);
    }
    
    private void updateEndTimeDisplay() {
        String timeText = String.format("%02d:%02d", selectedEndHour, selectedEndMinute);
        binding.editEndTime.setText(timeText);
    }

    private void populateTaskData(TaskEntity task) {
        if (task == null) return;

        // 保存原始值（用于自动保存）
        originalCreatedAt = task.getCreatedAt();
        originalEnabled = task.isEnabled();
        originalVolume = task.getVolume();

        binding.editTaskName.setText(task.getName());

        // 解析开始时间
        String[] startTimeParts = task.getStartTime().split(":");
        if (startTimeParts.length == 2) {
            selectedStartHour = Integer.parseInt(startTimeParts[0]);
            selectedStartMinute = Integer.parseInt(startTimeParts[1]);
            updateStartTimeDisplay();
        }
        
        // 解析结束时间
        String[] endTimeParts = task.getEndTime().split(":");
        if (endTimeParts.length == 2) {
            selectedEndHour = Integer.parseInt(endTimeParts[0]);
            selectedEndMinute = Integer.parseInt(endTimeParts[1]);
            updateEndTimeDisplay();
        }

        // 播放模式
        currentPlayMode = task.getPlayMode();
        updatePlayModeDisplay();

        // 重复日期
        currentRepeatDays = task.getRepeatDays();
        updateRepeatDaysDisplay();

        // 音频文件
        List<String> paths = Converters.parseAudioPaths(task.getAudioPaths());
        audioPaths.clear();
        audioPaths.addAll(paths);
        audioAdapter.notifyDataSetChanged();
        updateMusicListVisibility();

        // 播放设备
        currentOutputDevice = task.getOutputDevice();
        updateOutputDeviceDisplay();
        updateBluetoothStatus();

        // 全天播放
        binding.switchAllDayPlay.setChecked(task.isAllDayPlay());
        // 根据全天播放状态显示/隐藏时间范围
        binding.layoutTimeRange.setVisibility(task.isAllDayPlay() ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 刷新蓝牙状态
        updateBluetoothStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }
}
