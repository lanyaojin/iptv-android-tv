# IPTV（仅直播 · Android TV）

只做一件事：打开电视就是直播频道。基于公开版 media3/ExoPlayer 重写，不依赖任何私有 AAR，可独立编译。

- 包名：`com.ying.iptv`　版本：`1.0.7 (8)`　minSdk 21 / targetSdk 34 / compileSdk 35
- 产物：`app/build/outputs/apk/release/app-release.apk`（纯 Java 实现，无 so，单个 APK 通吃 32/64 位）
- 无预置源：首次启动进入引导页，在设置里填入你自己的 M3U/M3U8 直播源地址即可

## 遥控器操作

| 按键 | 作用 |
|---|---|
| 上下键 / 频道键 | 上一个 / 下一个频道 |
| OK | 打开频道列表；多线路频道先弹出线路选择；播放失败时重试当前频道 |
| 左键 | 播放态切换当前频道的下一条线路 |
| 右键 | 打开频道列表 |
| 返回 | 关闭列表 → 再按两次退出应用 |
| 菜单键 | 打开设置 |
| 数字键 | 直接输入频道号（3 位内，1.2 秒后自动跳转） |
| INFO 键 | 显示频道信息栏（含时钟） |
| 列表内长按 OK | 收藏 / 取消收藏 |

## 功能

- **打开即播**：记住上次频道；首次启动播第 1 个台。
- **分组频道列表**：左侧分类（全部 / 我的收藏 / 各分组），右侧 4 列频道卡片，带台标。
- **智能归类**：按 `tvg-id` / 频道名把「央视频」并入「央视」，并把误分到央视频组的卫视、CGTN、CETV 等纠正到「卫视 / 国际 / 教育」。
- **多线路合并**：同一频道的多条来源（如 CCTV1 的央视频 + 咪咕）合并为一个频道，OK 弹线路选择、左键轮换、播放失败自动依次换线；换台横幅与信息栏显示当前线路名。
- **容错解析**：支持 `tvg-id` / `tvg-name` / `tvg-logo` / `group-title` / `#EXTGRP`，频道名含逗号、BOM、GBK 编码、CRLF 均可解析；忽略 `#EXTVLCOPT`、`#KODIPROP`。
- **断流重连**：连接超时/5xx/403 等可恢复错误自动重试 3 次（0.8s / 1.6s / 2.4s 退避）；HTTP 400/401/404/410、内容不是视频流等确定性错误立即提示，不空等。失败后按 OK 可手动重试。
- **HLS 识别**：除 `.m3u8` 外，也识别 `/hls/`、`/live/`、`/relay/`、`/proxy/` 等中转路径，以及末段为纯数字或无扩展名的中转地址（咪咕数字代理会 302 到带签名的 m3u8）；明确的文件流后缀（`.ts`/`.mp4` 等）仍按渐进式处理。
- **后台恢复**：从主屏/其他应用返回时自动恢复播放。
- **离线兜底**：直播源缓存 6 小时；网络异常时自动使用旧缓存，保证有台可看。
- **源管理**：多源增删改切换、强制刷新、记忆开关。

## 编译

前置：JDK 17+（本项目用 21 验证）、Android SDK Platform 35 + Build-Tools 35.0.0。

```bash
cd iptv-app
# local.properties（已在仓库外，含本机路径与签名口令，勿提交）
#   sdk.dir=...
#   storeFile=keystore/iptv-release.jks
#   keyAlias=iptv / storePassword=... / keyPassword=...

./gradlew :app:assembleRelease
# 可选：希望首次启动就自动加载自己的直播源（仅本机使用，勿把地址写进仓库）
./gradlew :app:assembleRelease -PdefaultSourceUrl=<你的M3U地址>
```

产物：`app/build/outputs/apk/release/app-release.apk`。

签名证书：`keystore/iptv-release.jks`（口令见本地 `local.properties`，不在仓库中）。升级安装必须用同一证书，请自行备份。

## 安装到电视

```bash
# 电视：设置 → 关于 → 连点版本号开启开发者选项 → 打开 USB 调试
adb connect <电视IP>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.ying.iptv/.MainActivity
```

## 代码结构

```
app/src/main/java/com/ying/iptv/
├── MainActivity.java              直播页：播放、换台、频道列表浮层、按键处理
├── SettingsActivity.java          设置：源管理、刷新、记忆、关于
├── data/
│   ├── Channel.java               频道模型（含收藏 key、频道号）
│   ├── Playlist.java              分组索引与按号查找
│   ├── M3uParser.java             M3U/M3U8 容错解析（纯 Java，可单测）
│   ├── Source.java                源配置
│   ├── Prefs.java                 本地偏好（源、上次频道、收藏）
│   └── PlaylistRepository.java    下载 + 6h 缓存 + 失败回退 + 编码嗅探
├── player/PlayerController.java   ExoPlayer 封装：HLS/Progressive 建源、UA、重连
└── ui/                            ChannelAdapter / GroupAdapter / ImageLoader
```

## 已知限制

- 暂不支持 EPG 节目单、回看（源里已有 `catchup` 参数，后续可接）、时移、多屏。
- 频道归类规则硬编码在 `M3uParser`，调整规则需要重新编译；后台自定义分组规则不会同步到客户端。
- 多线路频道每次换台默认回到首条线路，暂不记忆各频道上次成功的线路。
- 源地址编辑走系统输入法，电视遥控器输入不便；建议在设置里改一次即可，或用外接键盘。
- 仅实现直播，点播/弹幕/MPV 软解等 fork 功能不在此工程内。

## 模拟器实测结论（2026-11-10）

环境：Android TV 9 (API 28) x86_64 模拟器，1920×1080@320dpi（与小米电视同分辨率/密度档）。

| 验证项 | 结果 |
|---|---|
| 安装 / 启动 / 打开即播 | ✅ |
| HLS H.264 全屏播放（公共测试流） | ✅ 画面清晰，软硬解均走通 |
| 频道列表：分组面板 + 卡片网格 + 聚焦边框 | ✅ |
| 长按 OK 收藏（★标记、计数、Toast） | ✅ |
| 收藏分组过滤 | ✅ |
| 上下键切台 + 换台横幅（编号+台名） | ✅ |
| 数字键直选频道 | ✅ |
| 播放失败自动重连 3 次 + 错误提示 | ✅ |
| 空源引导页（聚焦"设置"按钮） | ✅ |
| 双击返回退出 | ✅ |
| 菜单键进设置、源管理 | ✅ |

注意：测试中发现部分局域网中转服务（如自建 NAS 直播聚合）会对媒体地址校验请求来源——本机访问正常（200），模拟器 NAT 出口被拒（403），与播放器无关；电视在内网直连不受影响。模拟器联测请用公共测试流，或把模拟器出口加入中转服务白名单。
