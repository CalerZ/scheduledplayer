package com.caleb.scheduledplayer.util;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 应用日志工具类
 * 将日志同时输出到Logcat和文件，支持文件轮转和自动清理
 */
public class AppLogger {
    private static final String TAG = "AppLogger";
    private static final String LOG_DIR = "logs";
    private static final String LOG_FILE_PREFIX = "app_";
    private static final String LOG_FILE_SUFFIX = ".log";
    private static final long MAX_FILE_SIZE = 500 * 1024 * 1024; // 500MB
    private static final int RETENTION_DAYS = 7;

    private static volatile AppLogger instance;
    private Context context;
    private File logDir;
    private File currentLogFile;
    private BufferedWriter writer;
    private ExecutorService executor;
    private SimpleDateFormat timestampFormat;
    private SimpleDateFormat dateFormat;
    private String currentDate;

    private AppLogger() {
        executor = Executors.newSingleThreadExecutor();
        timestampFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        dateFormat = new SimpleDateFormat("yyyyMMdd", Locale.getDefault());
    }

    /**
     * 获取单例实例
     */
    public static AppLogger getInstance() {
        if (instance == null) {
            synchronized (AppLogger.class) {
                if (instance == null) {
                    instance = new AppLogger();
                }
            }
        }
        return instance;
    }

    /**
     * 初始化日志系统
     * @param context Application Context
     */
    public void init(Context context) {
        this.context = context.getApplicationContext();
        this.logDir = new File(context.getFilesDir(), LOG_DIR);
        
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
        
        // 初始化当前日志文件
        initCurrentLogFile();
        
        // 清理过期文件
        cleanOldFiles();
        
        Log.d(TAG, "AppLogger initialized, log dir: " + logDir.getAbsolutePath());
    }

    /**
     * 初始化或更新当前日志文件
     */
    private void initCurrentLogFile() {
        String today = dateFormat.format(new Date());
        
        if (!today.equals(currentDate)) {
            // 日期变化，关闭旧文件
            closeWriter();
            currentDate = today;
        }
        
        currentLogFile = new File(logDir, LOG_FILE_PREFIX + currentDate + LOG_FILE_SUFFIX);
        
        try {
            writer = new BufferedWriter(new FileWriter(currentLogFile, true));
        } catch (IOException e) {
            Log.e(TAG, "Failed to create log file writer", e);
        }
    }

    /**
     * 关闭当前写入器
     */
    private void closeWriter() {
        if (writer != null) {
            try {
                writer.flush();
                writer.close();
            } catch (IOException e) {
                Log.e(TAG, "Failed to close writer", e);
            }
            writer = null;
        }
    }

    /**
     * Debug 级别日志
     */
    public static void d(String tag, String msg) {
        Log.d(tag, msg);
        getInstance().writeToFile("D", tag, msg);
    }

    /**
     * Info 级别日志
     */
    public static void i(String tag, String msg) {
        Log.i(tag, msg);
        getInstance().writeToFile("I", tag, msg);
    }

    /**
     * Warning 级别日志
     */
    public static void w(String tag, String msg) {
        Log.w(tag, msg);
        getInstance().writeToFile("W", tag, msg);
    }

    /**
     * Error 级别日志
     */
    public static void e(String tag, String msg) {
        Log.e(tag, msg);
        getInstance().writeToFile("E", tag, msg);
    }

    /**
     * Error 级别日志（带异常）
     */
    public static void e(String tag, String msg, Throwable tr) {
        Log.e(tag, msg, tr);
        getInstance().writeToFile("E", tag, msg + "\n" + Log.getStackTraceString(tr));
    }

    /**
     * 异步写入日志到文件
     */
    private void writeToFile(String level, String tag, String msg) {
        if (executor == null || executor.isShutdown()) {
            return;
        }
        
        final String timestamp = timestampFormat.format(new Date());
        final String logLine = String.format("%s %s/%s: %s", timestamp, level, tag, msg);
        
        executor.execute(() -> {
            try {
                // 检查日期是否变化
                String today = dateFormat.format(new Date());
                if (!today.equals(currentDate)) {
                    initCurrentLogFile();
                }
                
                // 检查文件大小，需要时轮转
                checkRotation();
                
                if (writer != null) {
                    writer.write(logLine);
                    writer.newLine();
                    writer.flush();
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to write log", e);
            }
        });
    }

    /**
     * 检查文件大小，超过限制时轮转
     */
    private void checkRotation() {
        if (currentLogFile != null && currentLogFile.exists() && currentLogFile.length() > MAX_FILE_SIZE) {
            closeWriter();
            
            // 重命名当前文件，添加时间戳后缀
            String timestamp = new SimpleDateFormat("HHmmss", Locale.getDefault()).format(new Date());
            String newName = LOG_FILE_PREFIX + currentDate + "_" + timestamp + LOG_FILE_SUFFIX;
            File rotatedFile = new File(logDir, newName);
            currentLogFile.renameTo(rotatedFile);
            
            // 创建新文件
            currentLogFile = new File(logDir, LOG_FILE_PREFIX + currentDate + LOG_FILE_SUFFIX);
            try {
                writer = new BufferedWriter(new FileWriter(currentLogFile, true));
            } catch (IOException e) {
                Log.e(TAG, "Failed to create new log file after rotation", e);
            }
        }
    }

    /**
     * 清理过期日志文件
     */
    public void cleanOldFiles() {
        if (logDir == null || !logDir.exists()) {
            return;
        }
        
        executor.execute(() -> {
            long cutoffTime = System.currentTimeMillis() - (RETENTION_DAYS * 24 * 60 * 60 * 1000L);
            File[] files = logDir.listFiles((dir, name) -> name.startsWith(LOG_FILE_PREFIX) && name.endsWith(LOG_FILE_SUFFIX));
            
            if (files != null) {
                for (File file : files) {
                    if (file.lastModified() < cutoffTime) {
                        if (file.delete()) {
                            Log.d(TAG, "Deleted old log file: " + file.getName());
                        }
                    }
                }
            }
        });
    }

    /**
     * 获取日志目录
     */
    public File getLogDir() {
        return logDir;
    }

    /**
     * 获取所有日志文件（按日期倒序）
     */
    public List<File> getLogFiles() {
        if (logDir == null || !logDir.exists()) {
            return Collections.emptyList();
        }
        
        File[] files = logDir.listFiles((dir, name) -> name.startsWith(LOG_FILE_PREFIX) && name.endsWith(LOG_FILE_SUFFIX));
        
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }
        
        List<File> fileList = new ArrayList<>(Arrays.asList(files));
        // 按修改时间倒序排列
        Collections.sort(fileList, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
        return fileList;
    }

    /**
     * 清空所有日志文件
     */
    public void clearAllLogs() {
        closeWriter();
        
        if (logDir != null && logDir.exists()) {
            File[] files = logDir.listFiles((dir, name) -> name.startsWith(LOG_FILE_PREFIX) && name.endsWith(LOG_FILE_SUFFIX));
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
        }
        
        // 重新初始化当前日志文件
        currentDate = null;
        initCurrentLogFile();
    }

    /**
     * 获取日志文件总大小
     * @return 总大小（字节）
     */
    public long getTotalLogSize() {
        if (logDir == null || !logDir.exists()) {
            return 0;
        }
        
        long totalSize = 0;
        File[] files = logDir.listFiles((dir, name) -> name.startsWith(LOG_FILE_PREFIX) && name.endsWith(LOG_FILE_SUFFIX));
        
        if (files != null) {
            for (File file : files) {
                totalSize += file.length();
            }
        }
        
        return totalSize;
    }

    /**
     * 格式化文件大小
     */
    public static String formatFileSize(long size) {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f MB", size / (1024.0 * 1024));
        } else {
            return String.format(Locale.getDefault(), "%.1f GB", size / (1024.0 * 1024 * 1024));
        }
    }

    /**
     * 读取日志条目（用于UI显示）
     * @param maxLines 最大行数
     * @return 日志条目列表
     */
    public List<LogEntry> readLogEntries(int maxLines) {
        List<LogEntry> entries = new ArrayList<>();
        List<File> files = getLogFiles();
        
        for (File file : files) {
            if (entries.size() >= maxLines) {
                break;
            }
            
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                List<String> lines = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
                
                // 倒序读取
                for (int i = lines.size() - 1; i >= 0 && entries.size() < maxLines; i--) {
                    LogEntry entry = LogEntry.parse(lines.get(i));
                    if (entry != null) {
                        entries.add(entry);
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to read log file: " + file.getName(), e);
            }
        }
        
        return entries;
    }

    /**
     * 日志条目模型类
     */
    public static class LogEntry {
        public String timestamp;
        public String level;
        public String tag;
        public String message;

        public LogEntry(String timestamp, String level, String tag, String message) {
            this.timestamp = timestamp;
            this.level = level;
            this.tag = tag;
            this.message = message;
        }

        /**
         * 从日志行解析
         * 格式: 2026-01-18 10:30:45.123 D/AudioService: message content
         */
        public static LogEntry parse(String line) {
            if (line == null || line.isEmpty()) {
                return null;
            }
            
            try {
                // 解析时间戳 (前23个字符)
                if (line.length() < 26) {
                    return new LogEntry("", "?", "", line);
                }
                
                String timestamp = line.substring(0, 23);
                String rest = line.substring(24);
                
                // 解析级别和标签
                int slashIndex = rest.indexOf('/');
                int colonIndex = rest.indexOf(':');
                
                if (slashIndex > 0 && colonIndex > slashIndex) {
                    String level = rest.substring(0, slashIndex);
                    String tag = rest.substring(slashIndex + 1, colonIndex);
                    String message = colonIndex + 2 < rest.length() ? rest.substring(colonIndex + 2) : "";
                    return new LogEntry(timestamp, level, tag, message);
                } else {
                    return new LogEntry(timestamp, "?", "", rest);
                }
            } catch (Exception e) {
                return new LogEntry("", "?", "", line);
            }
        }

        /**
         * 获取完整日志行
         */
        public String getFullLine() {
            return String.format("%s %s/%s: %s", timestamp, level, tag, message);
        }

        /**
         * 是否包含关键字（用于搜索）
         */
        public boolean contains(String keyword) {
            if (keyword == null || keyword.isEmpty()) {
                return true;
            }
            String lowerKeyword = keyword.toLowerCase();
            return (tag != null && tag.toLowerCase().contains(lowerKeyword)) ||
                   (message != null && message.toLowerCase().contains(lowerKeyword));
        }
    }

    /**
     * 关闭日志系统
     */
    public void shutdown() {
        closeWriter();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
}
