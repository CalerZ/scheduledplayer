package com.caleb.scheduledplayer.util;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地音乐扫描工具
 * 扫描设备上的音乐文件并按目录组织成歌单
 */
public class MusicScanner {

    /**
     * 歌单（目录）数据模型
     */
    public static class Playlist {
        private final String path;       // 完整目录路径
        private final String name;       // 目录名称
        private final List<MusicFile> musicFiles;

        public Playlist(String path, String name) {
            this.path = path;
            this.name = name;
            this.musicFiles = new ArrayList<>();
        }

        public String getPath() {
            return path;
        }

        public String getName() {
            return name;
        }

        public List<MusicFile> getMusicFiles() {
            return musicFiles;
        }

        public void addMusicFile(MusicFile file) {
            musicFiles.add(file);
        }

        public int getFileCount() {
            return musicFiles.size();
        }
    }

    /**
     * 音乐文件数据模型
     */
    public static class MusicFile {
        private final long id;
        private final String title;
        private final String artist;
        private final String path;
        private final Uri uri;
        private final long duration;
        private final long size;

        public MusicFile(long id, String title, String artist, String path, Uri uri, long duration, long size) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.path = path;
            this.uri = uri;
            this.duration = duration;
            this.size = size;
        }

        public long getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public String getArtist() {
            return artist;
        }

        public String getPath() {
            return path;
        }

        public Uri getUri() {
            return uri;
        }

        public long getDuration() {
            return duration;
        }

        public long getSize() {
            return size;
        }

        public String getFileName() {
            if (path != null) {
                int lastSlash = path.lastIndexOf('/');
                if (lastSlash >= 0 && lastSlash < path.length() - 1) {
                    return path.substring(lastSlash + 1);
                }
            }
            return title != null ? title : "未知文件";
        }

        /**
         * 格式化时长显示
         */
        public String getFormattedDuration() {
            long seconds = duration / 1000;
            long minutes = seconds / 60;
            seconds = seconds % 60;
            return String.format("%d:%02d", minutes, seconds);
        }
    }

    /**
     * 扫描本地音乐并按目录组织成歌单
     */
    public static List<Playlist> scanMusic(Context context) {
        Map<String, Playlist> playlistMap = new LinkedHashMap<>();

        ContentResolver resolver = context.getContentResolver();
        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE
        };

        // 只查询音乐文件（排除铃声等）
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
        String sortOrder = MediaStore.Audio.Media.DATA + " ASC";

        try (Cursor cursor = resolver.query(uri, projection, selection, null, sortOrder)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
                int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);

                do {
                    long id = cursor.getLong(idColumn);
                    String title = cursor.getString(titleColumn);
                    String artist = cursor.getString(artistColumn);
                    String path = cursor.getString(dataColumn);
                    long duration = cursor.getLong(durationColumn);
                    long size = cursor.getLong(sizeColumn);

                    if (path == null || path.isEmpty()) continue;

                    // 获取目录路径
                    String dirPath = getDirectoryPath(path);
                    if (dirPath == null) continue;

                    // 获取或创建歌单
                    Playlist playlist = playlistMap.get(dirPath);
                    if (playlist == null) {
                        String dirName = getDirectoryName(dirPath);
                        playlist = new Playlist(dirPath, dirName);
                        playlistMap.put(dirPath, playlist);
                    }

                    // 构建 Content URI
                    Uri contentUri = Uri.withAppendedPath(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            String.valueOf(id)
                    );

                    // 添加音乐文件
                    MusicFile musicFile = new MusicFile(id, title, artist, path, contentUri, duration, size);
                    playlist.addMusicFile(musicFile);

                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 转换为列表并按路径排序
        List<Playlist> playlists = new ArrayList<>(playlistMap.values());
        Collections.sort(playlists, (a, b) -> a.getPath().compareToIgnoreCase(b.getPath()));

        return playlists;
    }

    /**
     * 获取文件所在目录路径
     */
    private static String getDirectoryPath(String filePath) {
        if (filePath == null) return null;
        int lastSlash = filePath.lastIndexOf('/');
        if (lastSlash > 0) {
            return filePath.substring(0, lastSlash);
        }
        return null;
    }

    /**
     * 获取目录名称
     */
    private static String getDirectoryName(String dirPath) {
        if (dirPath == null) return "未知目录";
        int lastSlash = dirPath.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < dirPath.length() - 1) {
            return dirPath.substring(lastSlash + 1);
        }
        return dirPath;
    }

    /**
     * 简化路径显示（移除存储前缀）
     */
    public static String simplifyPath(String path) {
        if (path == null) return "";
        
        // 常见存储路径前缀
        String[] prefixes = {
                "/storage/emulated/0",
                "/storage/emulated/legacy",
                "/sdcard",
                Environment.getExternalStorageDirectory().getAbsolutePath()
        };

        for (String prefix : prefixes) {
            if (path.startsWith(prefix)) {
                String simplified = path.substring(prefix.length());
                return simplified.isEmpty() ? "/" : simplified;
            }
        }

        return path;
    }
}
