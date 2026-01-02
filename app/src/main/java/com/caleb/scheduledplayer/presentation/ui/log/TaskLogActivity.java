package com.caleb.scheduledplayer.presentation.ui.log;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.caleb.scheduledplayer.R;
import com.caleb.scheduledplayer.data.repository.TaskLogRepository;
import com.google.android.material.appbar.MaterialToolbar;

/**
 * 任务执行日志列表页面
 */
public class TaskLogActivity extends AppCompatActivity {

    public static final String EXTRA_TASK_ID = "task_id";
    public static final String EXTRA_TASK_NAME = "task_name";

    private RecyclerView recyclerViewLogs;
    private LinearLayout layoutEmpty;
    private ProgressBar progressBar;
    private TaskLogAdapter adapter;
    private TaskLogRepository logRepository;
    private long taskId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_task_log);

        // 获取传入的任务信息
        taskId = getIntent().getLongExtra(EXTRA_TASK_ID, -1);
        String taskName = getIntent().getStringExtra(EXTRA_TASK_NAME);

        if (taskId == -1) {
            finish();
            return;
        }

        initViews();
        setupToolbar(taskName);
        setupRecyclerView();
        loadLogs();
    }

    private void initViews() {
        recyclerViewLogs = findViewById(R.id.recyclerViewLogs);
        layoutEmpty = findViewById(R.id.layoutEmpty);
        progressBar = findViewById(R.id.progressBar);
        logRepository = new TaskLogRepository(getApplication());
    }

    private void setupToolbar(String taskName) {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        if (taskName != null && !taskName.isEmpty()) {
            toolbar.setTitle(taskName + " - " + getString(R.string.task_log_title));
        }
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    private void setupRecyclerView() {
        adapter = new TaskLogAdapter();
        recyclerViewLogs.setLayoutManager(new LinearLayoutManager(this));
        recyclerViewLogs.setAdapter(adapter);
    }

    private void loadLogs() {
        progressBar.setVisibility(View.VISIBLE);
        
        logRepository.getLogsByTaskId(taskId).observe(this, logs -> {
            progressBar.setVisibility(View.GONE);
            
            if (logs == null || logs.isEmpty()) {
                recyclerViewLogs.setVisibility(View.GONE);
                layoutEmpty.setVisibility(View.VISIBLE);
            } else {
                recyclerViewLogs.setVisibility(View.VISIBLE);
                layoutEmpty.setVisibility(View.GONE);
                adapter.submitList(logs);
            }
        });
    }
}
