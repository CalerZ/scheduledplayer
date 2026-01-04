# 实施计划 - 蓝牙连接保持功能

## 实施概览
预计总工时：8-10小时
关键里程碑：
1. 基础设施（设置管理、静音资源）
2. 核心功能（静音播放、重连管理）
3. UI 界面（设置页面）
4. 服务集成与通知

## 任务列表

### 1. 基础设施

- [x] 1.1 创建 AppSettings 全局设置管理类
  - 创建 `util/AppSettings.java`
  - 实现 SharedPreferences 读写
  - 两个开关：`keep_bluetooth_alive`、`bluetooth_auto_reconnect`
  - 默认值均为 `true`
  - _需求：FR-4 验收标准1,2,3,4_

- [x] 1.2 添加静音音频资源
  - 创建 `res/raw/silence.wav`（1秒静音音频）
  - _需求：FR-1 验收标准1_

- [x] 1.3 添加字符串资源
  - 在 `res/values/strings.xml` 添加设置页面相关文本
  - 添加通知相关文本
  - _需求：FR-3, FR-4_

### 2. 核心功能实现

- [x] 2.1 创建 SilentAudioPlayer 静音音频播放器
  - 创建 `service/player/SilentAudioPlayer.java`
  - 实现 `start()`、`stop()`、`pause()`、`resume()` 方法
  - 使用 MediaPlayer 循环播放静音音频
  - 设置音量为 0
  - _需求：FR-1 验收标准1,2,3,4_

- [x] 2.2 扩展 BluetoothHelper 监听接口
  - 修改 `BluetoothConnectionListener` 接口，添加设备信息参数
  - 新增 `getLastConnectedDevice()` 方法
  - 在广播接收器中提取设备信息
  - _需求：FR-2, FR-3_

- [x] 2.3 创建 BluetoothReconnectManager 重连管理器
  - 创建 `util/BluetoothReconnectManager.java`
  - 实现设备信息保存和获取
  - 通过 BluetoothA2dp 代理尝试重连（反射调用）
  - 实现重连超时和回调机制
  - _需求：FR-2 验收标准1,2,3,4_

### 3. UI 界面

- [x] 3.1 创建设置页面布局
  - 创建 `res/layout/activity_settings.xml`
  - 包含 Toolbar、两个带描述的 Switch 开关
  - Material Design 风格
  - _需求：FR-4 验收标准1,2_

- [x] 3.2 创建 SettingsActivity
  - 创建 `presentation/ui/settings/SettingsActivity.java`
  - 绑定布局，初始化开关状态
  - 监听开关变化，保存设置
  - 设置变化时通知服务更新
  - _需求：FR-4 验收标准1,2,3_

- [x] 3.3 更新侧边栏菜单
  - 修改 `res/menu/menu_drawer.xml`，添加"设置"菜单项
  - 添加设置图标 `ic_settings`
  - _需求：FR-4_

- [x] 3.4 更新 MainActivity 菜单处理
  - 修改 `onNavigationItemSelected()` 处理设置菜单点击
  - 跳转到 SettingsActivity
  - _需求：FR-4_

### 4. 服务集成

- [x] 4.1 AudioPlaybackService 集成静音播放
  - 初始化 AppSettings 和 SilentAudioPlayer
  - 服务启动时根据设置启动静音播放
  - 任务播放时暂停静音，任务结束时恢复
  - 监听设置变化动态调整
  - _需求：FR-1 验收标准1,2,3,4_

- [x] 4.2 AudioPlaybackService 集成蓝牙重连
  - 初始化 BluetoothReconnectManager
  - 蓝牙断开时保存设备并尝试重连
  - 蓝牙重连成功时恢复音频路由
  - _需求：FR-2 验收标准1,2,3,4_

- [x] 4.3 实现蓝牙状态通知
  - 创建蓝牙状态通知方法
  - 蓝牙断开时发送通知（区分是否播放中）
  - 蓝牙重连成功时发送通知
  - _需求：FR-3 验收标准1,2,3_

### 5. 权限和配置

- [x] 5.1 更新 AndroidManifest.xml
  - 确保 BLUETOOTH_CONNECT 权限声明
  - 注册 SettingsActivity
  - _需求：NFR-2_

### 6. 测试验证

- [x] 6.1 功能测试
  - 测试设置页面开关保存和恢复
  - 测试静音音频播放启停
  - 测试蓝牙断开通知
  - 测试蓝牙重连功能
  - 测试任务播放时静音暂停/恢复
  - _需求：所有 FR 验收标准_
