package com.caleb.scheduledplayer.service.audio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 播放列表管理器
 * 管理音频文件的播放顺序，支持顺序播放和随机播放
 */
public class PlaylistManager {
    
    public enum PlayMode {
        SEQUENTIAL,  // 顺序播放
        RANDOM       // 随机播放
    }
    
    private final List<String> originalList;  // 原始列表
    private final List<String> playList;      // 实际播放列表
    private PlayMode playMode;
    private int currentIndex;
    private boolean isLooping;
    private final Random random;
    
    public PlaylistManager() {
        this.originalList = new ArrayList<>();
        this.playList = new ArrayList<>();
        this.playMode = PlayMode.SEQUENTIAL;
        this.currentIndex = 0;
        this.isLooping = true;
        this.random = new Random();
    }
    
    /**
     * 设置播放列表
     */
    public void setPlaylist(List<String> audioPaths) {
        originalList.clear();
        playList.clear();
        
        if (audioPaths != null) {
            originalList.addAll(audioPaths);
            playList.addAll(audioPaths);
        }
        
        currentIndex = 0;
        
        if (playMode == PlayMode.RANDOM) {
            shufflePlaylist();
        }
    }
    
    /**
     * 设置播放模式
     */
    public void setPlayMode(PlayMode mode) {
        if (this.playMode != mode) {
            this.playMode = mode;
            
            // 保存当前播放的文件
            String currentFile = getCurrentFile();
            
            if (mode == PlayMode.RANDOM) {
                shufflePlaylist();
            } else {
                // 恢复原始顺序
                playList.clear();
                playList.addAll(originalList);
            }
            
            // 尝试恢复到当前播放的文件位置
            if (currentFile != null) {
                int newIndex = playList.indexOf(currentFile);
                if (newIndex >= 0) {
                    currentIndex = newIndex;
                } else {
                    currentIndex = 0;
                }
            }
        }
    }
    
    /**
     * 设置是否循环播放
     */
    public void setLooping(boolean looping) {
        this.isLooping = looping;
    }
    
    /**
     * 获取当前文件
     */
    public String getCurrentFile() {
        if (playList.isEmpty() || currentIndex < 0 || currentIndex >= playList.size()) {
            return null;
        }
        return playList.get(currentIndex);
    }
    
    /**
     * 获取下一个文件
     */
    public String getNextFile() {
        if (playList.isEmpty()) {
            return null;
        }
        
        currentIndex++;
        
        if (currentIndex >= playList.size()) {
            if (isLooping) {
                currentIndex = 0;
                // 随机模式下，重新洗牌
                if (playMode == PlayMode.RANDOM) {
                    shufflePlaylist();
                }
            } else {
                currentIndex = playList.size() - 1;
                return null;  // 播放完毕
            }
        }
        
        return getCurrentFile();
    }
    
    /**
     * 获取上一个文件
     */
    public String getPreviousFile() {
        if (playList.isEmpty()) {
            return null;
        }
        
        currentIndex--;
        
        if (currentIndex < 0) {
            if (isLooping) {
                currentIndex = playList.size() - 1;
            } else {
                currentIndex = 0;
                return null;
            }
        }
        
        return getCurrentFile();
    }
    
    /**
     * 跳转到指定索引
     */
    public String skipTo(int index) {
        if (playList.isEmpty() || index < 0 || index >= playList.size()) {
            return null;
        }
        currentIndex = index;
        return getCurrentFile();
    }
    
    /**
     * 重置到开始位置
     */
    public void reset() {
        currentIndex = 0;
        if (playMode == PlayMode.RANDOM) {
            shufflePlaylist();
        }
    }
    
    /**
     * 洗牌播放列表
     */
    private void shufflePlaylist() {
        if (playList.size() > 1) {
            Collections.shuffle(playList, random);
        }
    }
    
    /**
     * 获取当前索引
     */
    public int getCurrentIndex() {
        return currentIndex;
    }
    
    /**
     * 获取播放列表大小
     */
    public int getSize() {
        return playList.size();
    }
    
    /**
     * 是否为空
     */
    public boolean isEmpty() {
        return playList.isEmpty();
    }
    
    /**
     * 获取播放模式
     */
    public PlayMode getPlayMode() {
        return playMode;
    }
    
    /**
     * 是否循环播放
     */
    public boolean isLooping() {
        return isLooping;
    }
    
    /**
     * 是否是最后一个文件
     */
    public boolean isLastFile() {
        return currentIndex >= playList.size() - 1;
    }
    
    /**
     * 是否是第一个文件
     */
    public boolean isFirstFile() {
        return currentIndex <= 0;
    }
    
    /**
     * 获取播放列表副本
     */
    public List<String> getPlaylist() {
        return new ArrayList<>(playList);
    }
}
