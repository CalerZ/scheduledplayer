package com.caleb.scheduledplayer.data.converter;

import androidx.room.TypeConverter;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * Room TypeConverter
 * 用于转换复杂类型
 */
public class Converters {

    /**
     * 将字符串列表转换为 JSON 字符串
     */
    @TypeConverter
    public static String fromStringList(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        JSONArray jsonArray = new JSONArray(list);
        return jsonArray.toString();
    }

    /**
     * 将 JSON 字符串转换为字符串列表
     */
    @TypeConverter
    public static List<String> toStringList(String json) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            JSONArray jsonArray = new JSONArray(json);
            List<String> list = new ArrayList<>();
            for (int i = 0; i < jsonArray.length(); i++) {
                list.add(jsonArray.getString(i));
            }
            return list;
        } catch (JSONException e) {
            return new ArrayList<>();
        }
    }

    /**
     * 解析音频路径 JSON 字符串
     * 工具方法，供其他类使用
     */
    public static List<String> parseAudioPaths(String audioPaths) {
        return toStringList(audioPaths);
    }

    /**
     * 将音频路径列表转换为 JSON 字符串
     * 工具方法，供其他类使用
     */
    public static String toAudioPathsJson(List<String> paths) {
        return fromStringList(paths);
    }
}
