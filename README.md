# Home KTV (家庭局域网智能点歌与曲库系统 - 增强版)

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Vue 3](https://img.shields.io/badge/Vue-3.x-emerald.svg)](https://vuejs.org/)
[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED.svg)](https://www.docker.com/)

> **项目致谢与二次开发说明：**  
> 本项目基于优秀的开源项目 [zhayinggang/ktv-home](https://github.com/zhayinggang/ktv-home) 进行了深度二次开发与功能扩展。诚挚感谢原作者的开源贡献！  
> 本版本在完整保留原项目出色的局域网多端协同、电视大屏逐字歌词渲染、局域网设备自发现等能力的基础上，重点围绕 **在线 MV 聚合搜索与极速下载中心**、**单轨 MV 转双轨伴奏系统 (Vocal Separation)**、**手机端点歌全链路交互体验升级**、**系统配置持久化** 与 **飞牛 fnOS (NAS) 容器化专属编排** 进行了全方位的架构重构与特性增强。

## 界面预览

| 手机点歌首页 | Android TV 待机页 | 曲库与服务仪表盘 |
| --- | --- | --- |
| ![手机点歌首页](docs/images/mobile-songbook.png) | ![Android TV 待机页](docs/images/tv-player.png) | ![管理仪表盘](docs/images/admin-dashboard.png) |
| 搜索、分类、收藏、点歌和遥控都在手机浏览器完成。 | 实机待机界面展示点歌二维码、服务状态与推荐歌曲。 | 统一查看曲库、转码任务和播放服务状态。 |

Home KTV 是一套运行在家庭 NAS 或 Linux 主机上的局域网点歌系统。电视负责播放，手机通过微信扫码进入点歌页，服务端管理曲库、队列、歌词、播放记录和系统设置。

系统由三个客户端组成：

- **服务端**：Spring Boot、PostgreSQL、FFmpeg/FFprobe、WebSocket
- **手机端**：Vue 3 H5 点歌页和管理后台，无需安装 App
- **电视端**：Android TV 客户端，基于 Media3/ExoPlayer

> 项目面向可信家庭局域网，未提供公网登录和安全防护，请勿直接暴露到互联网。

## 典型使用流程

1. 在 NAS 或 Linux 主机启动 Home KTV，通过管理后台扫描并整理本地曲库。
2. Android TV 客户端自动发现局域网服务，连接后在大屏上显示点歌二维码。
3. 家人用微信或手机浏览器扫码加入，各自搜歌、收藏和点歌，无需安装 App。
4. 点歌队列、播放进度、歌词、音量与原唱/伴唱状态在电视和手机间实时同步。
5. 演唱结束后可在手机查看最近演唱，管理员则可在后台维护歌曲、歌手、歌单和转码任务。

---

## ✨ 二开核心新增功能

### 1. 🎬 在线 MV 搜索与极速下载中心
- **多平台聚合搜索**：支持网易云音乐、Bilibili 等主流平台 MV 聚合检索，支持直链音画流解析与封面匹配。
- **B 站官方扫码授权**：管理后台集成 B 站官方安全二维码登录，自动同步账号 Cookie 凭证，解锁 1080P/4K 高清画质与 Hi-Res 无损音频流。
- **高韧性下载引擎**：支持多任务异步并发、动态断点续传、B 站 412 频控智能避让自愈与 6 阶降级解析。
- **全流程入库闭环**：下载完成后自动触发元数据刮削、自动转码入库，支持“下载后自动加入播放队列”及“自动提取转双轨伴奏”。

### 2. 🎙️ 单轨 MV 转双轨伴奏系统 (Vocal Separation)
- **双音轨无损重构**：针对网络下载或本地已有的单音轨普通 MV，提供极速 DSP 频带声学消音与 SOTA 深度学习 AI 人声分离双引擎，一键无损生成包含“原唱轨 + 伴奏轨”的标准双音轨 KTV 视频。
- **后台批量处理与进度**：支持单曲即时转换与全量后台批量处理，实时回显进度百分比、耗时与音轨置信度，彻底解决普通 MV 无法消音跟唱的痛点。

### 3. 📱 手机点歌端全链路体验升级
- **8 宫格对称金刚区**：主页采用 8 宫格现代对称排布（热歌榜、语种、歌手、歌单、主题、最近唱过、我的收藏、遥控器）。
- **时段温情问候专区**：根据早间、午后、傍晚和深夜智能切换问候文案，配备专属快捷选歌专区。
- **声波律动 NowPlayingBar**：手机底部常驻播放条新增动态声波波形跳动，实时同步电视端原伴唱切换、切歌与播放进度。
- **一键优先插播与重复点歌拦截引导**：支持一键“优先插播”（置顶到下一首播放）；重复点歌时弹出贴心防误触提醒，并引导查看队列或优先插播。
- **本地搜索历史与热门推荐**：搜索栏支持本地搜索记录保存、一键清空及热门榜单推荐标签。

### 4. ⚙️ 系统基础配置与偏好持久化
- 将“MV 下载后自动入队”与“单音轨自动触发转双轨伴奏”配置由前端局部状态移入服务端基础配置表持久化存储，实现多端同步与全局继承。

### 5. 🐳 飞牛 fnOS (NAS) / Linux 容器化编排
- 专门提供 `docker-compose.fnos.yml` 生产级配置文件，完美支持复用宿主机已有 PostgreSQL（如端口 5433）或启动独立容器，支持 Intel 核显 `/dev/dri` 透传开启 VAAPI 硬件级极速转码。

### 6. 📚 工业级项目需求与架构文档
- 在 [doc/README.md](doc/README.md) 中完整归档了包括在线 MV 下载、单转双轨伴奏、移动端交互优化、服务器部署等 5 大核心维度的全套 PRD、系统架构图、接口契约、实施计划与质量验收报告。

---

## 主要功能

### 手机点歌与遥控

- 微信或浏览器扫码进入，无需注册
- 支持歌名、歌手、中文、全拼和拼音首字母搜索
- 点歌、顶歌、删除自己的歌曲、智能打散和多人队列
- 播放、暂停、重唱、切歌、音量及原唱/伴唱切换
- 收藏、最近演唱、一键再唱、热门榜单和公开歌单
- 逐行 LRC 与增强 LRC 逐字歌词
- 鼓掌、欢呼、倒彩、干杯等现场音效

### Android TV 播放

- 播放 MP4、MKV、MPEG、MP3、FLAC 等常见媒体
- 支持 MPEG-2、MP2 和双音轨 KTV 视频
- 原唱/伴奏无重新加载切换
- 当前句与下一句双行歌词，当前句连续扫色高亮
- 遥控器控制播放、队列、音量和原伴唱
- 断线重连、状态恢复、待机轮播和防烧屏微移
- 播放页显示“微信扫码点歌”二维码
- 支持 Android 8.0（API 26）及以上版本

### 曲库管理

- 原始素材分析、MD5 去重、自动直拷和批量转码
- 使用 FFprobe 识别容器、编码、时长、分辨率和音轨
- 自动读取媒体标签、封面及同名歌词侧车文件
- 识别 KTV 视频、普通 MV 和纯音频
- 音轨标记纠正、歌曲编辑、重新解析及失效文件处理
- AI 辅助分类、主题歌单和点唱统计
- PostgreSQL 持久化及数据库备份/恢复

## 系统结构

```text
手机浏览器 / 微信
        │ HTTP + WebSocket
        ▼
Home KTV 服务端 ───── PostgreSQL
        │
        ├── /source-music  原始素材目录
        ├── /music         可点播曲库目录
        │
        └── Android TV     视频、音轨、歌词和控制
```

服务端默认使用以下入口：

| 用途 | 地址 |
| --- | --- |
| 手机点歌 | `http://<主机IP>:8080/m` |
| 管理后台 | `http://<主机IP>:8080/m/admin` |
| 健康检查 | `http://<主机IP>:8080/api/health` |

## 快速开始

### 环境要求


- 运行环境：支持 Docker Compose 的 Linux 主机、飞牛 fnOS、群晖/威联通 NAS 或本地 PC。
- 硬件配置：建议最低 2 核 CPU、1GB 可用内存；若启用硬件转码，推荐 Intel 带核显处理器。
- 网络环境：服务端、Android TV、点歌手机必须位于同一局域网内。
- 电视端要求：Android TV 8.0（API 26）及以上版本。

---

### 方案一：飞牛 fnOS (NAS) Docker 部署（推荐）

本项目已内置针对飞牛 fnOS 及同类 NAS 环境优化定制的编排文件 `docker-compose.fnos.yml`：

1. **克隆项目到 NAS 目标目录**：
   ```bash
   git clone https://github.com/<你的用户名>/ktv-home.git
   cd ktv-home
   ```

2. **检查并配置环境变量**：
   `docker-compose.fnos.yml` 默认预设对接本地 PostgreSQL 实例（端口 5433），并透传核显 `/dev/dri`。如需自定义，可通过环境变量或直接修改 compose 文件中的连接信息：
   ```yaml
   environment:
     SPRING_DATASOURCE_URL: jdbc:postgresql://10.17.220.79:5433/ktv
     SPRING_DATASOURCE_USERNAME: ktv
     SPRING_DATASOURCE_PASSWORD: your-password
   ```

3. **启动容器**：
   ```bash
   docker compose -f docker-compose.fnos.yml up -d --build
   ```

4. **验证服务运行状态**：
   ```bash
   curl http://127.0.0.1:8080/api/health
   ```
   返回 `{"status":"UP"}` 即表示启动成功。

---

### 方案二：通用 Docker Compose 部署

1. **复制环境配置并初始化**：
   ```bash
   cp .env.example .env
   ```
   编辑 `.env`，设置媒体素材路径与数据库密码：
   ```dotenv
   KTV_SOURCE_MUSIC_DIR=/volume1/home-ktv/source-music
   KTV_MUSIC_DIR=/volume1/home-ktv/music
   KTV_DB_PASSWORD=your_secure_password
   ```

2. **启动完整堆栈（包含内置 PostgreSQL）**：
   ```bash
   docker compose up -d --build --wait
   ```

---

### 方案三：本地开发环境启动

若需要进行二次开发或本地调试：

1. **启动依赖数据库**：
   ```bash
   docker compose -f docker-compose.dev.yml up -d
   ```

2. **后端启动 (Spring Boot 3.5, 需 JDK 21+)**：
   ```bash
   cd backend
   ./mvnw spring-boot:run
   # 或在 Windows 下直接双击运行 start-dev.bat
   ```

3. **前端点歌端启动 (Vue 3, 需 Node.js 20+)**：
   ```bash
   cd h5
   npm install
   npm run dev
   # 或在 Windows 下直接双击运行 start-h5.bat
   ```

---

确认容器健康：

```bash
docker compose ps
curl http://127.0.0.1:${KTV_HTTP_PORT:-8080}/api/health
```

默认开放：

- TCP `8080`：H5、管理后台、API、WebSocket 和媒体流
- UDP `18888`：Android TV 局域网自动发现

NAS 防火墙需要允许这两个端口；使用自定义端口时以 `.env` 为准。

### 3. 导入歌曲

1. 把原始歌曲放入 `KTV_SOURCE_MUSIC_DIR`。
2. 打开 `http://<主机IP>:8080/m/admin`。
3. 在仪表盘执行“扫描源路径”。
4. 兼容文件会自动直拷到曲库；不兼容文件进入待转码列表。
5. 在“原始音乐管理”中执行单首、选中或批量转码。
6. 在“KTV 曲库”中检查歌名、歌手、媒体类型和原唱/伴奏音轨。

### 在线搜歌与下载（二开新增）

1. 打开管理后台，点击 **在线 MV 下载**。
2. 点击 **B站账号登录** 扫码授权，解锁 1080P/4K 高清与无损音轨。
3. 在搜索栏输入歌手或歌名，一键下载；下载完成后系统会自动刮削元数据并入库，亦可勾选自动转双轨伴奏。

### 单轨转双轨伴奏（二开新增）

1. 在后台“KTV 曲库”或“在线 MV 下载”中，找到单音轨普通 MV 视频。
2. 点击 **转双轨伴奏**，系统调用 DSP 频带消音与 AI 分离模型无损重构生成原伴唱双轨视频。
3. 转换完成后可在手机点歌端或电视大屏上自由切换原唱与伴奏。

扫描只负责分析、去重和直拷，不会自动启动耗时转码。批量转码进度可在管理后台查看，任务运行时支持把指定歌曲插到下一首处理。

源目录自动监听默认关闭。是否启用请在管理后台“系统设置”中的“源目录自动扫描”开关调整；关闭时只有手动点击“扫描源路径”才会扫描，不通过 Compose 或环境变量配置。

确认入库结果后，可点击批量转码按钮右侧的“自动清理”释放原始素材目录空间。系统只会删除已成功入库、关联曲库记录有效且曲库输出文件真实存在的源文件；待转码、失败、重复、未识别、曲库文件缺失或路径校验不通过的素材会保留。转码任务运行期间不能执行自动清理。清理完成后，请回到仪表盘重新执行“扫描源路径”，同步原始目录中的最新文件。

### 4. 安装 Android TV 客户端

正式发布镜像已经内置同版本的 32 位（`armeabi-v7a`）和 64 位（`arm64-v8a`）
Release APK。发布流水线使用发布标签生成 `versionName`，使用 GitHub Actions 运行序号生成
单调递增的 `versionCode`，并将相同版本信息写入服务端镜像和两份 APK。

后端通过 `GET /api/release` 返回版本、公告和两个架构的安装包信息。公告 ID 默认等于版本号，
所以每次发布新版本都会再次显示。管理后台中的“稍后提醒”只在当前浏览器会话内隐藏公告，
“标记已读”会在当前浏览器保存该公告 ID，直到公告 ID 或版本号变化。公告仅在启用且镜像内
至少存在一份 APK 时弹出；源码开发镜像未放入 APK 时不会显示无效的下载公告。

默认公告除了提示下载 TV APK，还会提醒管理员：升级后进入“原始音乐管理”执行“自动清理”，
再回到仪表盘重新扫描原始音乐路径。公告配置随镜像内的 `application.yml` 发布，不依赖用户更新
`docker-compose.yml` 或 `.env`；拉取新镜像即可获得新版本号和公告内容。

TV 每次连接服务端成功后会检查 `versionCode`。版本不一致时根据设备 ABI 选择安装包，用户点击
“去下载”后，客户端会校验下载大小、申请未知来源安装权限，并打开系统安装程序。查询或下载失败
不会影响播放，下载失败时可以重试。安装包也可直接访问：

```text
http://<主机IP>:8080/api/release/tv/apk/armeabi-v7a
http://<主机IP>:8080/api/release/tv/apk/arm64-v8a
```

下载文件名分别为 `home-ktv-tv-<版本号>-armeabi-v7a.apk` 和
`home-ktv-tv-<版本号>-arm64-v8a.apk`。

首次安装 Release APK 后，后续版本必须继续使用同一签名证书。历史 Debug APK 使用
`.debug` 包名且签名不同，不能被 Release APK 直接覆盖，需要先卸载一次 Debug 版本再安装
Release 版本。卸载会清除 TV 端保存的服务端地址，歌曲和服务端数据不受影响。

本地构建 Debug APK 需要 JDK 17 和 Android SDK：

```bash
cd android-tv
./gradlew testDebugUnitTest assembleDebug
```

APK 输出位置：

```text
android-tv/app/build/outputs/apk/debug/app-debug.apk
```

通过 ADB 安装：

```bash
adb connect <TV_IP>:5555
adb install -r android-tv/app/build/outputs/apk/debug/app-debug.apk
```

也可以通过 U 盘或电视文件管理器安装。首次启动会尝试自动发现服务端；发现失败时填写 `<主机IP>:8080`。部分电视盒子需要额外允许未知来源、自启动和后台运行。

### 5. 开始点歌

TV 连接成功后会显示二维码。手机使用微信扫码进入点歌页，选择歌曲后电视自动播放；队列、播放状态、歌词和遥控操作通过 WebSocket 实时同步。

## 媒体与歌词

### 文件命名

系统优先读取媒体标签，标签缺失时从文件名推断歌手和歌名。推荐格式：

```text
歌手 - 歌名.mp4
歌手 - 歌名.mkv
歌手 - 歌名.mp3
```

示例：

```text
source-music/
├── 周杰伦 - 晴天.mp4
├── S.H.E - Super Star.mpg
└── Beyond - 海阔天空.mkv
```

同一首歌存在多个版本时，双音轨 KTV 视频优先于普通 MV 和纯音频。

### 双音轨约定

推荐的视频音轨顺序：

1. 原唱
2. 伴奏

同时建议写入音轨标题 `原唱`、`伴奏` 和语言标签 `zho`。系统会自动判断伴奏轨；识别错误时可在手机遥控页纠正并保存。

仓库提供一个基础声道相减脚本，可为满足声道条件的立体声 MV 生成双音轨文件：

```bash
./scripts/make_ktv_mv.sh input.mp4 output.mp4
```

该脚本不是 AI 人声分离，效果取决于原音频的声道混音方式，不能替代官方伴奏或专业分轨。

### 歌词侧车文件

歌词文件与媒体同名并放在同一目录：

```text
周杰伦 - 晴天.mp4
周杰伦 - 晴天.lrc
```

支持普通逐行 LRC：

```text
[00:12.50]故事的小黄花
```

支持增强 LRC 逐字时间：

```text
[00:12.50]<00:12.50>故<00:12.80>事<00:13.10>的<00:13.35>小<00:13.60>黄<00:13.90>花
```

增强 LRC 会在 TV 上以整句连续扫色显示，手机歌词页也会按字同步。

## 配置参考

常用环境变量：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `KTV_SOURCE_MUSIC_DIR` | `./source-music` | 宿主机原始素材目录 |
| `KTV_MUSIC_DIR` | `./music` | 宿主机可点播曲库目录 |
| `KTV_DATA_DIR` | `./data` | 宿主机应用数据目录 |
| `KTV_PG_DIR` | `./postgres` | 宿主机 PostgreSQL 数据目录 |
| `KTV_HTTP_PORT` | `8080` | Web、API、WebSocket 和媒体流端口 |
| `KTV_DISCOVERY_UDP_PORT` | `18888` | TV 自动发现 UDP 端口 |
| `KTV_DISCOVERY_NAME` | `家庭KTV` | TV 发现列表中的名称 |
| `KTV_DB_NAME` | `ktv` | PostgreSQL 数据库名 |
| `KTV_DB_USER` | `ktv` | PostgreSQL 用户名 |
| `KTV_DB_PASSWORD` | `ktv` | PostgreSQL 密码，正式部署必须修改 |
| `KTV_IMAGE_REGISTRY` | `docker.m.daocloud.io` | Docker 基础镜像仓库前缀 |
| `KTV_APP_IMAGE` | `home-ktv:latest` | 应用镜像名称 |
| `KTV_RELEASE_IMAGE` | `ghcr.io/zhayinggang/ktv-home:latest` | 预编译 Compose 使用的 GitHub 容器镜像 |
| `JAVA_TOOL_OPTIONS` | `-XX:MaxRAMPercentage=70 -Xmx512m` | 容器 JVM 内存参数 |

二维码默认使用 TV 访问服务端时的局域网 Host 地址。若网络中存在反向代理或多个网卡，可在管理后台设置“展示地址”，例如 `192.168.1.10:8080`。

## 硬件转码

默认使用 CPU 转码。Linux 主机可通过以下方式透传 VAAPI 设备：

> **验证范围：**目前只在 Intel 核显上验证了 VAAPI H.264 / HEVC 硬件编码，
> 使用 Intel `iHD` 驱动。AMD VAAPI 和 Rockchip RK MPP 尚未经过实机验证，
> 相关 Compose 配置仅表示支持设备透传，不保证预编译镜像可以直接启用硬件编码。

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.hardware.yml \
  up -d --build --wait
```

宿主机需要提供 `/dev/dri`。Intel 设备还需要容器内存在 `iHD_drv_video.so`；
官方预编译的 AMD64 镜像会安装 `intel-media-driver`。启动后在管理后台“系统设置”
中检测并开启硬件加速；设备、驱动、权限或编码器不可用时系统会拒绝保存。

瑞芯微设备可使用：

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.rockchip.yml \
  up -d --build --wait
```

宿主机需要提供 `/dev/mpp_service`，并且 FFmpeg 必须包含对应的 RK MPP 编码器。通用镜像不保证包含 `h264_rkmpp` 或 `hevc_rkmpp`。

## AI 配置与降级

AI 默认关闭，不影响扫描、转码、入库、点歌和播放。推荐在管理后台“系统设置 → AI 模型”中配置；也可以使用环境变量连接任意 OpenAI-compatible Chat Completions 服务：

```dotenv
KTV_AI_ENABLED=true
KTV_AI_BASE_URL=https://ai.example/v1
KTV_AI_API_KEY=你的密钥
KTV_AI_BULK_MODEL=your-model-id
KTV_AI_REASONING_MODEL=
KTV_AI_JSON_MODE=AUTO
KTV_AI_BULK_CONCURRENCY=2
KTV_AI_REASONING_CONCURRENCY=1
KTV_AI_AUTO_APPLY_CONFIDENCE=0.90
```

模型 ID 不做固定枚举限制；增强模型留空时复用批量模型。后台可以尝试获取模型列表并检测鉴权、Chat Completions 和 JSON 输出能力，不支持模型列表或 JSON Mode 的服务仍可手工配置和自动回退。

管理后台保存的 API Key 使用 AES-256-GCM 加密，接口只返回配置状态和尾号。主密钥优先读取 `KTV_CONFIG_MASTER_KEY`，否则生成到数据目录的 `secrets/config.key`。不要删除或丢失该文件，否则已保存的 API Key 无法解密。

未配置 AI 或调用超时、限流、失败时，本地标签、文件名、歌词标签和目录解析仍可继续工作，不阻塞入库。自然语言主题歌单、歌手性别推断等没有等价本地判断能力的操作会提示先配置模型，不会伪造结果。

修改 `.env` 后重新部署：

```bash
docker compose up -d --build
```

密钥只应保存在本地 `.env` 或受控 Secret 中，不要提交到仓库。

## 日常运维

### 更新

```bash
git pull
docker compose up -d --build --wait
```

### 停止与启动

```bash
docker compose stop
docker compose start
```

移除容器但保留数据卷：

```bash
docker compose down
```

不要在需要保留数据时运行 `docker compose down -v`。

### 日志

```bash
docker compose logs -f ktv
docker compose logs -f db
```

### 备份与恢复

```bash
./scripts/backup.sh /volume1/backup/home-ktv
```

恢复指定备份：

```bash
./scripts/restore.sh \
  /volume1/backup/home-ktv/home-ktv-YYYYMMDD-HHMMSS.dump \
  --yes
```

数据库备份包含歌曲元数据、设置、歌单、队列和播放历史，不包含原始媒体文件。`source-music` 和 `music` 目录需要使用 NAS 自身的备份方案。应用启动时会自动执行 Flyway 升级；正式更新前仍建议先备份数据库和媒体目录。当前迁移保留历史源记录，不通过升级脚本批量删除业务数据。

## 本地开发

开发环境需要 Node.js 20+、JDK 21、JDK 17、Docker 和 Android SDK。

```bash
# PostgreSQL
docker compose -f docker-compose.dev.yml up -d

# 后端，JDK 21
cd backend
./mvnw spring-boot:run

# H5
cd h5
npm install
npm run dev

# Android TV，JDK 17
cd android-tv
./gradlew testDebugUnitTest assembleDebug
```

测试命令：

```bash
cd backend && ./mvnw test
cd h5 && npm test
cd android-tv && ./gradlew testDebugUnitTest
```

目录结构：

```text
backend/               # Spring Boot 3.5 服务端核心 (MV下载、双轨伴奏、曲库管理、WebSocket)
h5/                    # Vue 3 前端工程 (移动点歌端 + 响应式管理后台)
android-tv/            # Kotlin Android TV 播放端 (Media3/ExoPlayer 双轨秒切与歌词扫色)
doc/                   # 完整的需求与工程技术设计文档库
  ├── mv-search-and-download/          # 在线 MV 搜索与下载中心文档 (PRD/架构/契约/QA)
  ├── single-to-dual-track-conversion/ # 单轨转双轨伴奏系统文档
  ├── mobile-song-ordering-optimization/ # 移动端点歌体验优化文档
  ├── server-deployment/               # 服务器部署与运维文档
  ├── mv-download-settings-migration/  # 配置持久化迁移文档
  └── README.md                        # 文档索引与分类导航
scripts/               # 运维、备份与音视频处理辅助脚本
docker-compose.fnos.yml# 飞牛 fnOS (NAS) 生产环境专属 Compose
docker-compose.yml     # 通用标准容器编排
```

## 常见问题

### Git 报错 detected dubious ownership in repository

Windows 环境下由于账户所有权机制，在执行 Git 命令前可先执行：
```bash
git config --global --add safe.directory D:/ktv-home
```

### 手机扫码后打不开

- 确认手机和服务端在同一局域网。
- 确认二维码地址不是 Docker 的 `172.x` 容器地址。
- 在管理后台设置正确的“展示地址”。
- 检查 NAS 防火墙和 TCP 端口。

### TV 自动发现不到服务端

- 检查 UDP `18888` 是否放行。
- 确认路由器没有开启 AP 隔离或访客网络隔离。
- 在 TV 首次设置页手动输入 `<主机IP>:8080`。

### 原唱和伴奏相反

在手机遥控页点击“原唱和伴唱弄反了？”，系统会纠正当前文件的伴奏轨标记并保存。

### 视频无法播放或卡顿

- 在原始音乐管理中查看格式分析结果。
- 对不兼容文件执行转码。
- 检查 NAS CPU、磁盘和网络占用。
- Linux 主机可尝试启用 VAAPI 或 RK MPP 硬件转码。

### 歌词未显示

- 确认 LRC 与媒体文件基础名称完全相同。
- 检查时间标签格式是否为 `[mm:ss.xx]`。
- 修改歌词后重新扫描，系统支持同名侧车歌词更新。

## 已知限制

- 系统只适用于可信局域网，不应直接暴露到公网。
- AI 人声分离或声道相减生成的伴奏可能残留主唱，无法达到官方母带效果。
- 蓝牙麦克风延迟和音质取决于电视盒子固件，实时演唱优先使用 USB 或有线设备。
- 不同 KTV 视频的音轨顺序并不统一，首次导入后建议抽查。
- Android TV 自启动和后台保活可能需要盒子厂商的额外权限。

## 许可与媒体责任

1. 本项目代码采用 [MIT License](LICENSE) 开源协议，保留原作者及贡献者版权声明。
2. 请仅将系统用于个人家庭局域网娱乐及有合法权限的媒体播放。本系统在线搜索与转码功能仅供技术学习与个人研究使用，请勿用于商业侵权分发。
3. 鸣谢原项目：[zhayinggang/ktv-home](https://github.com/zhayinggang/ktv-home)。
