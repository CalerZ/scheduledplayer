package com.caleb.scheduledplayer.util;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 音频文件有效性检查工具
 * 用于验证音频文件是否存在且可访问
 */
public class AudioFileValidator {
    
    private static final String TAG = "AudioFileValidator";
    
    /**
     * 验证结果
     */
    public static class ValidationResult {
        private final List<String> validFiles;
        private final List<String> invalidFiles;
        
        public ValidationResult() {
            this.validFiles = new ArrayList<>();
            this.invalidFiles = new ArrayList<>();
        }
        
        public void addValid(String path) {
            validFiles.add(path);
        }
        
        public void addInvalid(String path) {
            invalidFiles.add(path);
        }
        
        public List<String> getValidFiles() {
            return validFiles;
        }
        
        public List<String> getInvalidFiles() {
            return invalidFiles;
        }
        
        public boolean hasInvalidFiles() {
            return !invalidFiles.isEmpty();
        }
        
        public boolean hasValidFiles() {
            return !validFiles.isEmpty();
        }
        
        public int getValidCount() {
            return validFiles.size();
        }
        
        public int getInvalidCount() {
            return invalidFiles.size();
        }
    }
    
    /**
     * 验证音频文件列表
     */
    public static ValidationResult validate(Context context, List<String> audioPaths) {
        ValidationResult result = new ValidationResult();
        
        if (audioPaths == null || audioPaths.isEmpty()) {
            return result;
        }
        
        for (String path : audioPaths) {
            if (isFileValid(context, path)) {
                result.addValid(path);
            } else {
                result.addInvalid(path);
                Log.w(TAG, "音频文件无效: " + path);
            }
        }
        
        return result;
    }
    
    /**
     * 检查单个文件是否有效
     */
    public static boolean isFileValid(Context context, String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        
        try {
            if (path.startsWith("content://")) {
                // Content URI
                return isContentUriValid(context, path);
            } else {
                // 普通文件路径
                return isFilePathValid(path);
            }
        } catch (Exception e) {
            Log.e(TAG, "检查文件有效性时出错: " + path, e);
            return false;
        }
    }
    
    /**
     * 检查 Content URI 是否有效
     */
    private static boolean isContentUriValid(Context context, String uriString) {
        try {
            Uri uri = Uri.parse(uriString);
            InputStream inputStream = context.getContentResolver().openInputStream(uri);
            if (inputStream != null) {
                inputStream.close();
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 检查普通文件路径是否有效
     */
    private static boolean isFilePathValid(String path) {
        File file = new File(path);
        return file.exists() && file.isFile() && file.canRead();
    }
    
    /**
     * 获取文件名（从路径或 URI 中提取）
     */
    public static String getFileName(Context context, String path) {
        if (path == null || path.isEmpty()) {
            return "未知文件";
        }
        
        try {
            if (path.startsWith("content://")) {
                // 尝试从 Content URI 获取文件名
                Uri uri = Uri.parse(path);
                String[] projection = {android.provider.OpenableColumns.DISPLAY_NAME};
                try (android.database.Cursor cursor = context.getContentResolver()
                        .query(uri, projection, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                        if (nameIndex >= 0) {
                            return cursor.getString(nameIndex);
                        }
                    }
                }
                // 如果无法获取，使用 URI 的最后一段
                String lastSegment = uri.getLastPathSegment();
                return lastSegment != null ? lastSegment : "未知文件";
            } else {
                // 普通文件路径
                File file = new File(path);
                return file.getName();
            }
        } catch (Exception e) {
            Log.e(TAG, "获取文件名时出错: " + path, e);
            return "未知文件";
        }
    }
    
    /**
     * 获取文件大小（字节）
     */
    public static long getFileSize(Context context, String path) {
        if (path == null || path.isEmpty()) {
            return 0;
        }
        
        try {
            if (path.startsWith("content://")) {
                Uri uri = Uri.parse(path);
                String[] projection = {android.provider.OpenableColumns.SIZE};
                try (android.database.Cursor cursor = context.getContentResolver()
                        .query(uri, projection, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                        if (sizeIndex >= 0) {
                            return cursor.getLong(sizeIndex);
                        }
                    }
                }
                return 0;
            } else {
                File file = new File(path);
                return file.length();
            }
        } catch (Exception e) {
            return 0;
        }
    }
    
    /**
     * 格式化文件大小
     */
    public static String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }
}
