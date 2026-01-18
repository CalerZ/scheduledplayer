package com.caleb.scheduledplayer.presentation.ui.log;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.caleb.scheduledplayer.R;
import com.caleb.scheduledplayer.util.AppLogger;
import com.google.android.material.appbar.MaterialToolbar;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 日志查看界面
 */
public class LogViewerActivity extends AppCompatActivity {

    private static final int MAX_LOG_ENTRIES = 500;

    private RecyclerView recyclerView;
    private LogAdapter adapter;
    private EditText etSearch;
    private TextView tvStorageSize;
    private TextView tvEmpty;
    private ProgressBar progressBar;

    private List<AppLogger.LogEntry> allLogs = new ArrayList<>();
    private List<AppLogger.LogEntry> filteredLogs = new ArrayList<>();

    private ExecutorService executor;
    private Handler mainHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_viewer);

        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        initViews();
        setupToolbar();
        setupSearch();
        loadLogs();
    }

    private void initViews() {
        recyclerView = findViewById(R.id.recyclerViewLogs);
        etSearch = findViewById(R.id.etSearch);
        tvStorageSize = findViewById(R.id.tvStorageSize);
        tvEmpty = findViewById(R.id.tvEmpty);
        progressBar = findViewById(R.id.progressBar);

        adapter = new LogAdapter(filteredLogs);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
    }

    private void setupToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    private void setupSearch() {
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                filterLogs(s.toString());
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_log_viewer, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_share) {
            shareLogs();
            return true;
        } else if (id == R.id.action_clear) {
            showClearConfirmDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * 加载日志
     */
    private void loadLogs() {
        showLoading(true);

        executor.execute(() -> {
            // 读取日志
            List<AppLogger.LogEntry> entries = AppLogger.getInstance().readLogEntries(MAX_LOG_ENTRIES);
            
            // 获取存储大小
            long totalSize = AppLogger.getInstance().getTotalLogSize();
            String sizeText = AppLogger.formatFileSize(totalSize);

            mainHandler.post(() -> {
                allLogs.clear();
                allLogs.addAll(entries);
                
                // 更新存储信息
                tvStorageSize.setText(sizeText);
                
                // 应用当前搜索过滤
                filterLogs(etSearch.getText().toString());
                
                showLoading(false);
            });
        });
    }

    /**
     * 过滤日志
     */
    private void filterLogs(String keyword) {
        filteredLogs.clear();
        
        if (keyword == null || keyword.isEmpty()) {
            filteredLogs.addAll(allLogs);
        } else {
            for (AppLogger.LogEntry entry : allLogs) {
                if (entry.contains(keyword)) {
                    filteredLogs.add(entry);
                }
            }
        }
        
        adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    /**
     * 分享日志
     */
    private void shareLogs() {
        executor.execute(() -> {
            try {
                // 创建临时文件用于分享
                File cacheDir = getCacheDir();
                File shareFile = new File(cacheDir, "app_logs.txt");
                
                // 写入所有日志到临时文件
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(shareFile))) {
                    List<AppLogger.LogEntry> entries = AppLogger.getInstance().readLogEntries(Integer.MAX_VALUE);
                    for (AppLogger.LogEntry entry : entries) {
                        writer.write(entry.getFullLine());
                        writer.newLine();
                    }
                }

                mainHandler.post(() -> {
                    try {
                        // 通过 FileProvider 分享
                        Uri uri = FileProvider.getUriForFile(
                                this,
                                getPackageName() + ".fileprovider",
                                shareFile
                        );

                        Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        shareIntent.setType("text/plain");
                        shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        
                        startActivity(Intent.createChooser(shareIntent, getString(R.string.log_share_title)));
                    } catch (Exception e) {
                        Toast.makeText(this, R.string.log_share_failed, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (IOException e) {
                mainHandler.post(() -> 
                    Toast.makeText(this, R.string.log_share_failed, Toast.LENGTH_SHORT).show()
                );
            }
        });
    }

    /**
     * 显示清空确认对话框
     */
    private void showClearConfirmDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.log_clear_confirm_title)
                .setMessage(R.string.log_clear_confirm_message)
                .setPositiveButton(R.string.ok, (dialog, which) -> clearLogs())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 清空日志
     */
    private void clearLogs() {
        executor.execute(() -> {
            AppLogger.getInstance().clearAllLogs();
            
            mainHandler.post(() -> {
                allLogs.clear();
                filteredLogs.clear();
                adapter.notifyDataSetChanged();
                
                tvStorageSize.setText(AppLogger.formatFileSize(0));
                updateEmptyState();
                
                Toast.makeText(this, R.string.log_cleared, Toast.LENGTH_SHORT).show();
            });
        });
    }

    /**
     * 显示/隐藏加载状态
     */
    private void showLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(loading ? View.GONE : View.VISIBLE);
    }

    /**
     * 更新空状态显示
     */
    private void updateEmptyState() {
        if (filteredLogs.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            tvEmpty.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
}
