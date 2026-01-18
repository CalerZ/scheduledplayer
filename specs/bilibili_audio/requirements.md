# 需求文档 - Bilibili 音频播放

## 概述
在定时播放任务中支持播放 Bilibili 视频的音频，用户可以通过输入视频链接或 BV 号添加 Bilibili 音频源，支持后台播放。

## 用户角色
- **普通用户**：希望定时播放 Bilibili 上的音乐、有声读物、白噪音等内容

## 功能需求

### FR-1: Bilibili 视频链接解析
**用户故事：** 作为用户，我想要通过输入 Bilibili 视频链接或 BV 号来添加音频源，以便播放 B 站上的内容

**验收标准：**
1. When 用户输入完整的 Bilibili 视频链接（如 `https://www.bilibili.com/video/BV1xxxxxx`）, the system shall 解析出 BV 号并获取视频信息
2. When 用户输入 BV 号（如 `BV1xxxxxx`）, the system shall 直接获取视频信息
3. When 解析成功, the system shall 显示视频标题、封面、时长等信息供用户确认
4. If 链接无效或视频不存在, then the system shall 显示错误提示"无法解析该链接，请检查是否正确"
5. If 视频需要会员或付费, then the system shall 提示"该视频需要登录或付费，暂不支持"

### FR-2: Bilibili 音频后台播放
**用户故事：** 作为用户，我想要在后台播放 Bilibili 音频，以便在定时任务中使用

**验收标准：**
1. When 任务触发播放 Bilibili 音频, the system shall 获取音频流地址并开始播放
2. While 播放 Bilibili 音频, the system shall 支持后台持续播放（屏幕关闭/切换应用）
3. When 网络断开, the system shall 暂停播放并在网络恢复后自动重试
4. If 音频流获取失败, then the system shall 记录错误日志并尝试重新获取（最多 3 次）

### FR-3: Bilibili 播放列表支持
**用户故事：** 作为用户，我想要添加多个 Bilibili 视频作为播放列表，以便连续播放

**验收标准：**
1. When 用户在任务中添加多个 Bilibili 链接, the system shall 按顺序组成播放列表
2. When 当前视频播放完毕, the system shall 自动播放下一个视频的音频
3. While 播放列表模式, the system shall 支持单曲循环、列表循环、顺序播放
4. When 用户混合添加本地音乐和 Bilibili 链接, the system shall 支持混合播放列表

### FR-4: Bilibili 音频源管理
**用户故事：** 作为用户，我想要管理已添加的 Bilibili 音频源，以便编辑和删除

**验收标准：**
1. When 用户查看音频源列表, the system shall 显示 Bilibili 视频的标题、封面缩略图、时长
2. When 用户长按 Bilibili 音频项, the system shall 显示删除选项
3. When 用户点击 Bilibili 音频项, the system shall 支持预览播放
4. While 显示 Bilibili 音频项, the system shall 标识其来源类型（显示 B 站图标）

### FR-5: 收藏夹导入（可选增强）
**用户故事：** 作为用户，我想要导入 Bilibili 收藏夹，以便批量添加音频

**验收标准：**
1. When 用户输入收藏夹链接, the system shall 解析并显示收藏夹内容列表
2. When 用户选择视频后确认, the system shall 将选中的视频添加到播放列表
3. If 收藏夹为私密, then the system shall 提示"该收藏夹为私密，无法访问"

## 非功能需求

### NFR-1: 性能要求
- 链接解析响应时间：< 3 秒
- 音频流获取时间：< 5 秒
- 支持最多 50 个 Bilibili 视频在单个播放列表中

### NFR-2: 网络要求
- 播放 Bilibili 音频需要网络连接
- 应支持 WiFi 和移动数据网络
- 建议在 WiFi 环境下使用以节省流量

### NFR-3: 兼容性要求
- 支持解析标准 Bilibili 视频链接格式
- 支持 BV 号格式（不支持旧版 AV 号）
- 不支持番剧、电影、直播等特殊内容

## 边界条件和异常处理
- **视频被删除**：播放时提示"视频已被删除"，自动跳到下一首
- **视频地区限制**：提示"该视频在您所在地区不可用"
- **网络超时**：重试 3 次后提示"网络连接超时，请检查网络"
- **音频流过期**：自动重新获取音频流地址（Bilibili 音频流地址有时效性）

## 技术约束
- Bilibili 未提供官方 API，需通过网页接口获取数据
- 音频流地址有时效性（约 2 小时），需要定期刷新
- 部分高清音频可能需要登录（暂不支持登录功能）

## 约束与注意事项
- 本功能仅供个人学习使用
- 不缓存/下载 Bilibili 音频到本地
- 遵守 Bilibili 用户协议
