<template>
  <AdminLayout active="mv">
    <!-- 页面标题区 / Page Header -->
    <header class="header-card">
      <div class="header-main">
        <div class="header-icon-box">🎬</div>
        <div>
          <div class="header-title-row">
            <h1>在线 MV 搜索与下载</h1>
            <span class="version-tag">全网聚合</span>
          </div>
          <p>聚合检索网易云音乐与哔哩哔哩官方 MV、现场高清版及 KTV 伴奏视频，一键下载并自动导入可点播曲库</p>
        </div>
      </div>
      <div class="header-meta">
        <div class="stat-pill">
          <span class="stat-num">{{ tasks.length }}</span>
          <span class="stat-label">总任务数</span>
        </div>
        <div class="stat-pill active">
          <span class="stat-num">{{ activeTasksCount }}</span>
          <span class="stat-label">正在处理</span>
        </div>
      </div>
    </header>

    <!-- 哔哩哔哩账号登录与音画质特权卡片 / Bilibili Account Status Banner -->
    <section class="bili-auth-banner" :class="{ 'is-logged-in': biliAccount?.isLoggedIn }">
      <div class="bili-auth-main">
        <div class="bili-brand-icon">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor">
            <path d="M17.813 4.653h.854c1.51 0 2.733 1.224 2.733 2.734v10.146c0 1.51-1.223 2.734-2.733 2.734H5.333C3.823 20.267 2.6 19.043 2.6 17.533V7.387c0-1.51 1.223-2.734 2.733-2.734h.854L4.314 2.78a.8.8 0 0 1 1.132-1.132L8.552 4.653h6.896l3.106-3.005a.8.8 0 1 1 1.132 1.132l-1.873 1.873zM5.333 6.253c-.626 0-1.133.508-1.133 1.134v10.146c0 .626.507 1.134 1.133 1.134h13.334c.626 0 1.133-.508 1.133-1.134V7.387c0-.626-.507-1.134-1.133-1.134H5.333zm3.2 4.534a1.333 1.333 0 1 1 0 2.666 1.333 1.333 0 0 1 0-2.666zm6.934 0a1.333 1.333 0 1 1 0 2.666 1.333 1.333 0 0 1 0-2.666z"/>
          </svg>
        </div>
        <div v-if="biliAccount?.isLoggedIn" class="bili-profile-info">
          <img v-if="biliAccount.face" :src="biliAccount.face" class="bili-avatar" alt="头像" referrerpolicy="no-referrer" />
          <div class="bili-text-wrap">
            <div class="bili-name-row">
              <strong class="bili-uname">{{ biliAccount.uname }}</strong>
              <span class="bili-vip-badge" :class="{ 'is-vip': biliAccount.vipStatus > 0 }">
                {{ biliAccount.vipLabel || (biliAccount.vipStatus > 0 ? '大会员' : '普通用户') }}
              </span>
              <span class="bili-security-badge">已启用抗拦截防护与高清 DASH 流解析</span>
            </div>
            <p class="bili-status-sub">
              B 站凭据已就绪，优先解析 1080P/4K 高清画质与 192k/320k 独立伴奏音轨，降低 412/403 拦截风险。
            </p>
          </div>
        </div>
        <div v-else class="bili-unlogin-info">
          <div class="bili-unlogin-title-row">
            <strong class="bili-unlogin-title">未连接哔哩哔哩账号 (游客模式)</strong>
            <span class="bili-warn-tag">易受 412 拦截 / 限制 480P</span>
          </div>
          <p class="bili-unlogin-sub">
            B 站对未登录游客进行了严格的清晰度限制与动态反爬拦截。扫码登录后可稳定解锁 1080P/4K 视频直链与高码率伴奏，下载更流畅。
          </p>
        </div>
      </div>
      <div class="bili-auth-actions">
        <button v-if="!biliAccount?.isLoggedIn" class="bili-primary-btn" @click="openQrModal">
          <QrCode :size="16" />
          <span>扫码登录 B 站</span>
        </button>
        <template v-else>
          <button class="bili-action-btn" :disabled="biliRefreshing" title="刷新账号信息" @click="refreshBiliStatus">
            <RefreshCw :size="14" :class="{ spinning: biliRefreshing }" />
            <span>{{ biliRefreshing ? '刷新中…' : '刷新状态' }}</span>
          </button>
          <button class="bili-action-btn danger" title="退出账号" @click="handleBiliLogout">
            <LogOut :size="14" />
            <span>退出登录</span>
          </button>
        </template>
      </div>
    </section>

    <!-- 搜索与偏好设置卡片 / Search and Settings Card -->
    <section class="search-section">
      <div class="search-bar-row">
        <div class="search-input-wrapper">
          <Search :size="18" class="search-icon" />
          <input
            v-model.trim="keyword"
            placeholder="输入歌名、歌手，例如“起风了”、“周杰伦 晴天 KTV”、“花海 伴奏”..."
            @keyup.enter="handleSearch"
          />
          <button v-if="keyword" class="clear-input-btn" title="清空" @click="keyword = ''">
            <X :size="15" />
          </button>
        </div>
        <div class="platform-tabs">
          <button
            type="button"
            class="tab-btn"
            :class="{ active: selectedProvider === 'ALL' }"
            @click="selectedProvider = 'ALL'"
          >
            全部平台
          </button>
          <button
            type="button"
            class="tab-btn netease-tab"
            :class="{ active: selectedProvider === 'NETEASE' }"
            @click="selectedProvider = 'NETEASE'"
          >
            <span class="dot red"></span>网易云
          </button>
          <button
            type="button"
            class="tab-btn bilibili-tab"
            :class="{ active: selectedProvider === 'BILIBILI' }"
            @click="selectedProvider = 'BILIBILI'"
          >
            <span class="dot blue"></span>哔哩哔哩
          </button>
        </div>
        <button class="search-submit-btn" :disabled="searching || !keyword" @click="handleSearch">
          <Search :size="16" />
          <span>{{ searching ? '检索中…' : '全网搜索' }}</span>
        </button>
      </div>

     <!-- 存储路径提示 -->
     <div class="preferences-row">
       <span class="pref-tip">视频将保存至原始音乐 source-music/downloads 目录，自动纳入原始音乐管理流水线</span>
        <div class="pref-switches">
          <label class="pref-toggle-label" title="在系统设置【基础设置 - MV 下载与点播】中统一生效">
            <input type="checkbox" :checked="globalSettings.mv_auto_convert_dual_track" @change="toggleAutoConvert" />
            <span class="pref-toggle-text">单音轨自动转双轨伴奏</span>
            <span class="pref-toggle-badge" :class="globalSettings.mv_auto_convert_dual_track ? 'on' : 'off'">
              {{ globalSettings.mv_auto_convert_dual_track ? '已开启 (' + (globalSettings.dual_track_engine || 'DSP') + ')' : '已关闭' }}
            </span>
          </label>
        </div>
     </div>
   </section>

    <!-- 搜索结果区域 / Search Results Grid -->
    <section v-if="hasSearched" class="results-section">
      <div class="section-title-bar">
        <div class="title-left">
          <h2>检索结果</h2>
          <span class="result-count">共匹配到 {{ searchResults.length }} 首视频资源</span>
        </div>
        <div v-if="searching" class="searching-badge">
          <RefreshCw :size="14" class="spinning" />
          <span>正在聚合搜索多源平台...</span>
        </div>
      </div>

      <div v-if="searchResults.length" class="mv-grid">
        <div v-for="item in searchResults" :key="item.provider + item.externalId" class="mv-card">
          <div class="mv-cover-box">
            <!-- 增加 referrerpolicy 防止 B 站防盗链，绑定 @error 在图片失败时回退到占位图标避免黑屏 -->
            <img
              v-if="item.coverUrl && !isCoverFailed(item)"
              :src="item.coverUrl"
              class="mv-cover"
              alt="封面"
              loading="lazy"
              referrerpolicy="no-referrer"
              @error="onCoverError(item)"
            />
            <div v-else class="mv-cover-fallback">
              <FileVideo2 :size="32" />
            </div>
            <span class="provider-tag" :class="item.provider.toLowerCase()">
              {{ providerName(item.provider) }}
            </span>
            <span class="duration-tag">{{ formatDuration(item.durationMs) }}</span>
          </div>
          <div class="mv-info">
            <h3 class="mv-title" :title="item.title">{{ item.title }}</h3>
            <div class="mv-author">
              <span class="author-label">歌手 / UP主：</span>
              <span class="author-name" :title="item.artist">{{ item.artist }}</span>
            </div>
          </div>
          <div class="mv-card-actions">
            <a :href="item.sourceUrl" target="_blank" rel="noopener" class="source-link">
              原网页预览 ↗
            </a>
            <div class="card-action-group">
              <!-- B 站视频支持选集下载，方便用户在合集中精准勾选目标集数 -->
              <button
                v-if="item.provider === 'BILIBILI'"
                type="button"
                class="parts-trigger-btn"
                title="查看该合集包含的全部曲目并勾选批量下载"
                @click="openPartsModal(item)"
              >
                <ListPlus :size="13" />
                <span>选集下载</span>
              </button>
              <button
                class="download-action-btn"
                :class="{ 'downloaded-btn': isDownloading(item) }"
                :disabled="isDownloading(item)"
                @click="submitDownload(item)"
              >
                <Check v-if="isDownloading(item)" :size="14" />
                <span>{{ isDownloading(item) ? '已提交' : (item.provider === 'BILIBILI' ? '整片点播' : '下载并点播') }}</span>
              </button>
            </div>
          </div>
        </div>
      </div>

      <div v-else-if="!searching" class="empty-results-box">
        <div class="empty-icon">🔍</div>
        <h3>未检索到相关视频</h3>
        <p>可以尝试更换搜索词，或精简歌手名、去除无用特殊符号后再试</p>
      </div>
    </section>

    <!-- 下载与合流任务看板 / Download Tasks Manager -->
    <section class="tasks-section">
      <div class="tasks-header">
        <div class="tasks-header-left">
          <h2>下载与合流任务</h2>
          <div class="task-filter-chips">
            <button
              class="chip-btn"
              :class="{ active: currentTaskFilter === 'ALL' }"
              @click="currentTaskFilter = 'ALL'"
            >
              全部 ({{ tasks.length }})
            </button>
            <button
              class="chip-btn"
              :class="{ active: currentTaskFilter === 'ACTIVE' }"
              @click="currentTaskFilter = 'ACTIVE'"
            >
              进行中 ({{ activeTasksCount }})
            </button>
            <button
              class="chip-btn"
              :class="{ active: currentTaskFilter === 'COMPLETED' }"
              @click="currentTaskFilter = 'COMPLETED'"
            >
              已入库 ({{ completedTasksCount }})
            </button>
            <button
              class="chip-btn"
              :class="{ active: currentTaskFilter === 'FAILED' }"
              @click="currentTaskFilter = 'FAILED'"
            >
              失败 ({{ failedTasksCount }})
            </button>
          </div>
        </div>
        <div class="tasks-header-right">
          <button class="refresh-tasks-btn" :disabled="tasksRefreshing" @click="fetchTasks">
            <RefreshCw :size="14" :class="{ spinning: tasksRefreshing }" />
            <span>刷新列表</span>
          </button>
        </div>
      </div>

      <div class="table-container">
        <table class="modern-table">
          <thead>
            <tr>
              <th style="width: 70px;">ID</th>
              <th>视频标题</th>
              <th style="width: 100px;">来源平台</th>
              <th style="width: 220px;">状态与进度</th>
              <th style="width: 130px;">下载速率</th>
              <th style="width: 160px;">文件大小</th>
              <th style="width: 100px;">提交时间</th>
              <th style="width: 140px; text-align: right;">管理操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in filteredTasks" :key="t.id" class="task-row">
              <td class="task-id-cell">#{{ t.id }}</td>
              <td class="title-cell">
                <div class="title-wrapper">
                  <strong class="row-title" :title="t.title">{{ t.title }}</strong>
                  <span class="row-artist" :title="t.artist">{{ t.artist }}</span>
                  <span v-if="t.targetFilePath" class="row-path" :title="'点击复制完整路径: ' + t.targetFilePath" @click="copyPath(t.targetFilePath)">
                    📁 {{ t.targetFilePath }}
                  </span>
                </div>
              </td>
              <td>
                <span class="provider-badge" :class="t.provider.toLowerCase()">
                  {{ providerName(t.provider) }}
                </span>
              </td>
             <td class="progress-cell">
               <div class="status-row">
                 <span class="status-badge" :class="t.status.toLowerCase()">
                   {{ statusName(t.status) }}
                 </span>
                 <span class="percent-text">{{ t.progress }}%</span>
                  <span v-if="t.autoConvertDualTrack" class="convert-dual-badge" title="单音轨自动转双轨伴奏已启用">转双轨</span>
                 <button
                   v-if="t.errorMessage"
                    class="error-tag-btn"
                    title="点击查看详细诊断原因"
                    @click="showErrorDetail(t)"
                  >
                    查看原因
                  </button>
                </div>
                <div class="progress-track">
                  <div
                    class="progress-fill"
                    :class="t.status.toLowerCase()"
                    :style="{ width: Math.max(t.progress, 3) + '%' }"
                  ></div>
                </div>
              </td>
              <td class="speed-cell">
                <span v-if="t.status === 'DOWNLOADING'" class="speed-val">{{ formatSpeed(t.speedBps) }}</span>
                <span v-else class="text-muted">—</span>
              </td>
              <td class="size-cell">
                <div class="size-wrapper">
                  <span class="size-main">{{ formatBytes(t.downloadedBytes) }}</span>
                  <span v-if="t.totalBytes > 0" class="size-total">/ {{ formatBytes(t.totalBytes) }}</span>
                </div>
              </td>
              <td class="time-cell">{{ formatTime(t.createdAt) }}</td>
              <td class="actions-cell">
                <div class="actions-group">
                  <button
                    v-if="t.status === 'DOWNLOADING' || t.status === 'PENDING'"
                    class="act-btn cancel"
                    @click="cancelTask(t.id)"
                  >
                    取消
                  </button>
                  <button
                    v-if="t.status === 'FAILED' || t.status === 'CANCELLED'"
                    class="act-btn retry"
                    @click="retryTask(t.id)"
                  >
                    <RotateCcw :size="12" />
                    <span>重试</span>
                  </button>
                  <button
                    v-if="t.status === 'COMPLETED' || t.status === 'FAILED' || t.status === 'CANCELLED'"
                    class="act-btn delete"
                    title="删除任务记录"
                    @click="deleteTask(t.id)"
                  >
                    <Trash2 :size="13" />
                  </button>
                </div>
              </td>
            </tr>
            <tr v-if="!filteredTasks.length">
              <td colspan="8" class="empty-table-cell">
                <div class="empty-task-placeholder">
                  <span>暂无符合条件的任务记录</span>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <!-- 错误诊断详情弹窗 / Error Details Modal -->
    <div v-if="errorDetailOpen" class="modal-overlay" @click.self="errorDetailOpen = false">
      <div class="detail-modal">
        <div class="detail-header">
          <div class="detail-title">
            <AlertTriangle :size="20" class="warn-icon" />
            <h3>任务异常诊断</h3>
          </div>
          <button class="modal-close" @click="errorDetailOpen = false">×</button>
        </div>
        <div class="detail-body">
          <div class="info-row">
            <span class="info-k">任务名称：</span>
            <strong class="info-v">{{ selectedErrorTask?.title }}</strong>
          </div>
          <div class="info-row">
            <span class="info-k">来源平台：</span>
            <span class="info-v">{{ providerName(selectedErrorTask?.provider) }}</span>
          </div>
          <div class="error-box">
            <div class="error-box-title">异常原因：</div>
            <pre class="error-code-block">{{ selectedErrorTask?.errorMessage }}</pre>
          </div>
          <div class="troubleshoot-box">
            <h4>💡 建议排查方法：</h4>
            <ul>
              <li v-if="selectedErrorTask?.errorMessage?.includes('FFmpeg')">
                <strong>缺少 FFmpeg 运行时</strong>：B 站 DASH 视频需要 FFmpeg 将视频轨和音频轨无损合流。若在 Windows 开发环境下运行，请下载 ffmpeg.exe 并配置系统环境变量，或使用 Docker 容器部署（Docker 镜像内已预装 FFmpeg）。
              </li>
              <li v-else-if="selectedErrorTask?.errorMessage?.includes('网易云')">
                <strong>网易云官方地址受限</strong>：部分独家或 VIP MV 可能临时限制直链拉取，可切换到 B 站搜索同名高清 KTV 伴奏视频进行下载。
              </li>
              <li v-else-if="selectedErrorTask?.provider === 'BILIBILI' && !biliAccount?.isLoggedIn">
                <strong>未登录 B 站账号</strong>：B 站游客模式对部分高清播放流和伴奏音轨设有 WAF/412 拦截或封锁。请在页面顶部点击<strong>【扫码登录 B 站】</strong>连接账号后，再点击下方“立即重试此任务”。
              </li>
              <li v-else-if="selectedErrorTask?.provider === 'BILIBILI' && selectedErrorTask?.errorMessage?.includes('未能解析')">
                <strong>资源版权或会员限制</strong>：该视频可能属于充电专属、大会员专享或受区域限制，建议尝试搜索并下载同名其它伴奏或 MV 版本。
              </li>
              <li v-else>
                <strong>网络超时或断开</strong>：检查当前网络连通性后，点击下方“立即重试”按钮重新发起下载。
              </li>
            </ul>
          </div>
        </div>
        <div class="detail-footer">
          <button class="btn-ghost" @click="errorDetailOpen = false">关闭</button>
          <button class="btn-primary" @click="retryFromModal">立即重试此任务</button>
        </div>
      </div>
    </div>

    <!-- B 站扫码登录弹窗 / Bilibili QR Login Modal -->
    <div v-if="qrModalOpen" class="modal-overlay" @click.self="closeQrModal">
      <div class="qr-modal-card">
        <div class="qr-modal-header">
          <div class="qr-header-left">
            <div class="qr-bili-logo">
              <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor">
                <path d="M17.813 4.653h.854c1.51 0 2.733 1.224 2.733 2.734v10.146c0 1.51-1.223 2.734-2.733 2.734H5.333C3.823 20.267 2.6 19.043 2.6 17.533V7.387c0-1.51 1.223-2.734 2.733-2.734h.854L4.314 2.78a.8.8 0 0 1 1.132-1.132L8.552 4.653h6.896l3.106-3.005a.8.8 0 1 1 1.132 1.132l-1.873 1.873zM5.333 6.253c-.626 0-1.133.508-1.133 1.134v10.146c0 .626.507 1.134 1.133 1.134h13.334c.626 0 1.133-.508 1.133-1.134V7.387c0-.626-.507-1.134-1.133-1.134H5.333zm3.2 4.534a1.333 1.333 0 1 1 0 2.666 1.333 1.333 0 0 1 0-2.666zm6.934 0a1.333 1.333 0 1 1 0 2.666 1.333 1.333 0 0 1 0-2.666z"/>
              </svg>
            </div>
            <h3>哔哩哔哩账号扫码登录</h3>
          </div>
          <button class="modal-close" @click="closeQrModal">×</button>
        </div>
        <div class="qr-modal-body">
          <div class="qr-canvas-box">
            <div v-if="qrLoading" class="qr-loading-state">
              <RefreshCw :size="30" class="spinning" />
              <span>正在向 B 站申请安全登录二维码…</span>
            </div>
            <div v-else-if="qrImg" class="qr-img-wrapper">
              <img :src="qrImg" alt="B站扫码登录" class="qr-img" />
              <div v-if="qrState === 86038" class="qr-mask expired">
                <span>二维码已失效</span>
                <button class="qr-refresh-btn" @click="fetchQrCode">点击刷新二维码</button>
              </div>
              <div v-else-if="qrState === 86090" class="qr-mask scanned">
                <Check :size="28" />
                <span>已扫码，请在手机端确认</span>
              </div>
              <div v-else-if="qrState === 0" class="qr-mask success">
                <Check :size="32" />
                <span>登录成功，正在同步…</span>
              </div>
            </div>
          </div>
          <div class="qr-status-indicator" :class="'state-' + qrState">
            <template v-if="qrState === 86101">
              <p>请打开 <strong>哔哩哔哩手机客户端</strong> 扫一扫</p>
            </template>
            <template v-else-if="qrState === 86090">
              <p class="scanned-tip">📱 手机端已扫描，请在手机上点击 <strong>【确认登录】</strong></p>
            </template>
            <template v-else-if="qrState === 0">
              <p class="success-tip">🎉 登录成功！账号凭据已持久化至 NAS</p>
            </template>
            <template v-else-if="qrState === 86038">
              <p class="expired-tip">⚠️ 二维码已超时失效，请点击上方刷新</p>
            </template>
            <template v-else>
              <p>{{ qrMessage || '正在等待扫码…' }}</p>
            </template>
          </div>
          <div class="qr-security-note">
            <p>🔒 <strong>安全声明</strong>：登录凭据仅加密保存在本地 Home KTV 服务器中，仅用于在线流媒体解析与规避风控，绝不上传第三方服务器。</p>
          </div>
        </div>
        <div class="qr-modal-footer">
          <button class="btn-ghost" @click="closeQrModal">关闭</button>
          <button v-if="qrState === 86038" class="btn-primary" @click="fetchQrCode">刷新二维码</button>
        </div>
      </div>
    </div>
    <!-- B 站视频分集/合集选集下载弹窗 / Bilibili Parts Selection Modal -->
    <div v-if="partsModalOpen" class="modal-overlay" @click.self="partsModalOpen = false">
      <div class="parts-modal-card">
        <!-- 弹窗头部 -->
        <div class="parts-modal-header">
          <div class="parts-header-info">
            <div class="parts-badge-title">
              <span class="provider-pill bilibili">B站合集</span>
              <h3 :title="currentMvItem?.title">{{ currentMvItem?.title }}</h3>
            </div>
            <p class="parts-subtitle">
              UP主：{{ currentMvItem?.artist }} · 共 {{ partsList.length }} 集曲目 · 已勾选 {{ selectedPartPages.size }} 集
            </p>
          </div>
          <button class="parts-close-btn" title="关闭" @click="partsModalOpen = false">
            <X :size="18" />
          </button>
        </div>

        <!-- 弹窗过滤与快捷操作栏 -->
        <div class="parts-toolbar">
          <div class="parts-filter-wrap">
            <Search :size="14" class="filter-search-icon" />
            <input
              v-model.trim="partSearchText"
              type="text"
              placeholder="搜索曲目名、歌手或第几集 (如 晴天、周杰伦、01)..."
              class="parts-filter-input"
            />
            <button v-if="partSearchText" class="parts-clear-btn" title="清空搜索" @click="partSearchText = ''">
              <X :size="13" />
            </button>
          </div>
          <div class="parts-shortcuts">
            <button type="button" class="parts-btn-ghost" @click="selectAllParts">全选全部</button>
            <button type="button" class="parts-btn-ghost" @click="clearPartSelection">清空已选</button>
            <button type="button" class="parts-btn-ghost" @click="invertPartSelection">反选</button>
            <button v-if="filteredParts.length >= 10" type="button" class="parts-btn-ghost" @click="selectTopNParts(10)">选前10集</button>
            <button v-if="filteredParts.length >= 20" type="button" class="parts-btn-ghost" @click="selectTopNParts(20)">选前20集</button>
          </div>
        </div>

        <!-- 全局偏好开关 -->
        <div class="parts-options-row">
          <label class="parts-opt-label">
            <input v-model="partsAutoConvert" type="checkbox" />
            <span>单音轨自动转双轨伴奏（启用 DSP / AI 人声分离）</span>
          </label>
          <label class="parts-opt-label">
            <input v-model="partsAutoEnqueue" type="checkbox" />
            <span>下载入库后自动加入点歌队列</span>
          </label>
        </div>

        <!-- 分集列表展示区 -->
        <div class="parts-list-body">
          <div v-if="partsLoading" class="parts-loading-box">
            <RefreshCw :size="24" class="spinning" />
            <span>正在解析 B 站视频分集信息，请稍候…</span>
          </div>
          <div v-else-if="filteredParts.length === 0" class="parts-empty-box">
            <span>未匹配到符合条件的分集曲目</span>
          </div>
          <div v-else class="parts-items-grid">
            <div
              v-for="p in filteredParts"
              :key="p.page"
              class="part-item-row"
              :class="{ selected: selectedPartPages.has(p.page) }"
              @click="togglePartSelection(p.page)"
            >
              <div class="part-check-col">
                <input
                  type="checkbox"
                  :checked="selectedPartPages.has(p.page)"
                  @click.stop="togglePartSelection(p.page)"
                />
              </div>
              <span class="part-page-badge">P{{ p.page }}</span>
              <div class="part-info-col">
                <strong class="part-title-text" :title="p.part">{{ p.part }}</strong>
              </div>
              <span class="part-duration-text">{{ formatDuration(p.durationMs) }}</span>
            </div>
          </div>
        </div>

        <!-- 弹窗底部操作区 -->
        <div class="parts-modal-footer">
          <div class="parts-footer-left">
            <span>已选中 <strong>{{ selectedPartPages.size }}</strong> / {{ partsList.length }} 集</span>
          </div>
          <div class="parts-footer-right">
            <button type="button" class="btn-ghost" @click="partsModalOpen = false">取消</button>
            <button
              type="button"
              class="btn-primary"
              :disabled="selectedPartPages.size === 0 || partsSubmitting"
              @click="submitBatchPartsDownload"
            >
              <RefreshCw v-if="partsSubmitting" :size="14" class="spinning" />
              <span>{{ partsSubmitting ? '正在提交…' : ('批量下载所选 (' + selectedPartPages.size + '集)') }}</span>
            </button>
          </div>
        </div>
      </div>
    </div>
  </AdminLayout>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import AdminLayout from './AdminLayout.vue'
import { api } from '../../api/client'
import {
  Search, X, FileVideo2, RefreshCw, Check, RotateCcw, Trash2, AlertTriangle, QrCode, LogOut, ListPlus
} from 'lucide-vue-next'

const keyword = ref('')
const selectedProvider = ref('ALL')
const searching = ref(false)
const hasSearched = ref(false)
const searchResults = ref([])

// 记录封面图片加载失败的视频ID集合，加载失败时优雅降级为占位图标，杜绝页面渲染崩溃
const failedCoverKeys = ref(new Set())

const onCoverError = (item) => {
  if (!item) return
  const key = (item.provider || '') + '_' + (item.externalId || '')
  failedCoverKeys.value.add(key)
}

const isCoverFailed = (item) => {
  if (!item) return false
  const key = (item.provider || '') + '_' + (item.externalId || '')
  return failedCoverKeys.value.has(key)
}
const tasks = ref([])
const currentTaskFilter = ref('ALL')
const tasksRefreshing = ref(false)
const submittedExternalIds = ref(new Set())

const globalSettings = ref({
  mv_auto_convert_dual_track: false,
  mv_auto_enqueue: true,
  dual_track_engine: 'DSP'
})

const fetchGlobalSettings = async () => {
  try {
    const res = await api.adminGetSettings()
    if (res) {
      globalSettings.value = {
        mv_auto_convert_dual_track: Boolean(res.mv_auto_convert_dual_track),
        mv_auto_enqueue: res.mv_auto_enqueue !== false,
        dual_track_engine: res.dual_track_engine || 'DSP'
      }
    }
  } catch (e) {
    console.warn('获取全局设置失败:', e)
  }
}

const toggleAutoConvert = async (e) => {
  const nextVal = e.target.checked
  try {
    await api.adminPutSettings({ mv_auto_convert_dual_track: nextVal })
    globalSettings.value.mv_auto_convert_dual_track = nextVal
  } catch (err) {
    e.target.checked = !nextVal
    console.error('切换转双轨开关失败:', err)
  }
}

const biliAccount = ref({ isLoggedIn: false })
const biliRefreshing = ref(false)
const qrModalOpen = ref(false)
const qrLoading = ref(false)
const qrImg = ref('')
const qrKey = ref('')
const qrState = ref(86101)
const qrMessage = ref('')
let qrPollTimer = null

// ===== B 站分P/合集选集下载状态与逻辑 =====
const partsModalOpen = ref(false)
const partsLoading = ref(false)
const partsSubmitting = ref(false)
const currentMvItem = ref(null)
const partsList = ref([])
const partSearchText = ref('')
const selectedPartPages = ref(new Set())
const partsAutoConvert = ref(true)
const partsAutoEnqueue = ref(true)

// 过滤后的分集列表（支持实时歌名/序号过滤）
const filteredParts = computed(() => {
  if (!partSearchText.value.trim()) return partsList.value
  const kw = partSearchText.value.trim().toLowerCase()
  return partsList.value.filter(p => {
    const text = ('p' + p.page + ' ' + (p.part || '')).toLowerCase()
    return text.includes(kw)
  })
})

// 打开选集弹窗并拉取分P信息
const openPartsModal = async (item) => {
  currentMvItem.value = item
  partsModalOpen.value = true
  partsLoading.value = true
  partsList.value = []
  partSearchText.value = ''
  selectedPartPages.value = new Set()
  partsAutoConvert.value = Boolean(globalSettings.value.mv_auto_convert_dual_track)
  partsAutoEnqueue.value = Boolean(globalSettings.value.mv_auto_enqueue)

  try {
    const res = await api.getMvParts(item.provider, item.externalId)
    const list = res.parts || []
    partsList.value = list
    // 默认勾选前 1 集
    if (list.length > 0) {
      selectedPartPages.value.add(list[0].page)
    }
  } catch (e) {
    alert('获取分集列表失败: ' + (e.message || '网络异常'))
    partsModalOpen.value = false
  } finally {
    partsLoading.value = false
  }
}

const togglePartSelection = (page) => {
  if (selectedPartPages.value.has(page)) {
    selectedPartPages.value.delete(page)
  } else {
    selectedPartPages.value.add(page)
  }
}

const selectAllParts = () => {
  filteredParts.value.forEach(p => selectedPartPages.value.add(p.page))
}

const clearPartSelection = () => {
  selectedPartPages.value.clear()
}

const invertPartSelection = () => {
  filteredParts.value.forEach(p => {
    if (selectedPartPages.value.has(p.page)) {
      selectedPartPages.value.delete(p.page)
    } else {
      selectedPartPages.value.add(p.page)
    }
  })
}

const selectTopNParts = (n) => {
  selectedPartPages.value.clear()
  filteredParts.value.slice(0, n).forEach(p => selectedPartPages.value.add(p.page))
}

// 批量提交勾选的分集下载任务
const submitBatchPartsDownload = async () => {
  if (!currentMvItem.value || selectedPartPages.value.size === 0) return
  partsSubmitting.value = true

  const selectedParts = partsList.value.filter(p => selectedPartPages.value.has(p.page))
  const requests = selectedParts.map(p => {
    const extId = currentMvItem.value.externalId + '?p=' + p.page + '&cid=' + p.cid
    submittedExternalIds.value.add(extId)
    return {
      provider: currentMvItem.value.provider,
      externalId: extId,
      title: p.part || (currentMvItem.value.title + ' P' + p.page),
      artist: currentMvItem.value.artist || '未知UP主',
      coverUrl: p.coverUrl || currentMvItem.value.coverUrl,
      resolution: currentMvItem.value.resolution || '1080p',
      autoEnqueue: partsAutoEnqueue.value,
      autoConvertDualTrack: partsAutoConvert.value,
      durationMs: p.durationMs
    }
  })

  try {
    const createdTasks = await api.batchDownloadMv(requests)
    submittedExternalIds.value.add(currentMvItem.value.externalId)
    partsModalOpen.value = false
    alert('成功提交 ' + createdTasks.length + ' 个分集下载任务！已加入下载合流队列。')
    fetchTasks()
  } catch (e) {
    alert('批量提交分集下载失败: ' + (e.message || '网络异常'))
  } finally {
    partsSubmitting.value = false
  }
}

const errorDetailOpen = ref(false)
const selectedErrorTask = ref(null)

let timer = null

const activeTasksCount = computed(() => {
  return tasks.value.filter(t => t.status === 'DOWNLOADING' || t.status === 'PENDING' || t.status === 'MERGING' || t.status === 'IMPORTING').length
})

const completedTasksCount = computed(() => {
  return tasks.value.filter(t => t.status === 'COMPLETED').length
})

const failedTasksCount = computed(() => {
  return tasks.value.filter(t => t.status === 'FAILED').length
})

const filteredTasks = computed(() => {
  if (currentTaskFilter.value === 'ACTIVE') {
    return tasks.value.filter(t => t.status === 'DOWNLOADING' || t.status === 'PENDING' || t.status === 'MERGING' || t.status === 'IMPORTING')
  }
  if (currentTaskFilter.value === 'COMPLETED') {
    return tasks.value.filter(t => t.status === 'COMPLETED')
  }
  if (currentTaskFilter.value === 'FAILED') {
    return tasks.value.filter(t => t.status === 'FAILED')
  }
  return tasks.value
})

const fetchBiliStatus = async () => {
  try {
    const res = await api.getBilibiliAuthStatus()
    if (res) {
      biliAccount.value = res
    }
  } catch (e) {
    console.warn('获取 B 站登录状态失败:', e)
  }
}

const refreshBiliStatus = async () => {
  biliRefreshing.value = true
  try {
    const res = await api.refreshBilibiliAuthStatus()
    if (res) {
      biliAccount.value = res
    }
  } catch (e) {
    alert('刷新 B 站账号状态失败: ' + (e.message || '网络异常'))
  } finally {
    biliRefreshing.value = false
  }
}

const handleBiliLogout = async () => {
  if (!confirm('确定退出当前 B 站登录账号吗？退出后下载解析将恢复为游客低画质模式。')) return
  try {
    await api.logoutBilibiliAuth()
    biliAccount.value = { isLoggedIn: false }
    alert('已成功退出 B 站账号登录')
  } catch (e) {
    alert('退出登录失败: ' + (e.message || '网络异常'))
  }
}

const openQrModal = () => {
  qrModalOpen.value = true
  fetchQrCode()
}

const closeQrModal = () => {
  qrModalOpen.value = false
  stopQrPolling()
}

const fetchQrCode = async () => {
  stopQrPolling()
  qrLoading.value = true
  qrState.value = 86101
  qrMessage.value = '请使用哔哩哔哩客户端扫码'
  qrImg.value = ''
  qrKey.value = ''
  try {
    const res = await api.getBilibiliAuthQrCode()
    qrImg.value = res.qrImgBase64
    qrKey.value = res.qrcodeKey
    startQrPolling(res.qrcodeKey)
  } catch (e) {
    qrMessage.value = '获取二维码失败: ' + (e.message || '网络超时')
  } finally {
    qrLoading.value = false
  }
}

const startQrPolling = (key) => {
  stopQrPolling()
  qrPollTimer = setInterval(async () => {
    try {
      const res = await api.pollBilibiliAuthQrCode(key)
      qrState.value = res.code
      qrMessage.value = res.message
      if (res.code === 0) {
        // 登录成功
        stopQrPolling()
        if (res.account) {
          biliAccount.value = res.account
        } else {
          await fetchBiliStatus()
        }
        setTimeout(() => {
          qrModalOpen.value = false
        }, 1500)
      } else if (res.code === 86038) {
        // 二维码失效
        stopQrPolling()
      }
    } catch (e) {
      console.warn('轮询 B 站扫码异常:', e)
    }
  }, 2000)
}

const stopQrPolling = () => {
  if (qrPollTimer) {
    clearInterval(qrPollTimer)
    qrPollTimer = null
  }
}

const handleSearch = async () => {
  if (!keyword.value) return
  searching.value = true
  hasSearched.value = true
  failedCoverKeys.value.clear()
  try {
    const res = await api.searchMv(keyword.value, selectedProvider.value, 24)
    searchResults.value = res.items || []
  } catch (e) {
    alert('搜索失败: ' + (e.message || '未知网络错误'))
  } finally {
    searching.value = false
  }
}

const submitDownload = async (item) => {
  try {
    await api.downloadMv({
      provider: item.provider,
      externalId: item.externalId,
      title: item.title,
      artist: item.artist,
      coverUrl: item.coverUrl,
      resolution: item.resolution
    })
    submittedExternalIds.value.add(item.provider + item.externalId)
    await fetchTasks()
  } catch (e) {
    alert('提交下载失败: ' + (e.message || '系统错误'))
  }
}

const isDownloading = (item) => {
  return submittedExternalIds.value.has(item.provider + item.externalId)
}

const fetchTasks = async () => {
  tasksRefreshing.value = true
  try {
    const list = await api.listMvTasks()
    tasks.value = list || []
  } catch (e) {
    console.error('获取任务列表失败', e)
  } finally {
    setTimeout(() => { tasksRefreshing.value = false }, 400)
  }
}

const cancelTask = async (id) => {
  if (!confirm('确定取消该下载任务吗？')) return
  try {
    await api.cancelMvTask(id)
    await fetchTasks()
  } catch (e) {
    alert('取消失败: ' + e.message)
  }
}

const retryTask = async (id) => {
  try {
    await api.retryMvTask(id)
    await fetchTasks()
  } catch (e) {
    alert('重试任务失败: ' + e.message)
  }
}

const deleteTask = async (id) => {
  if (!confirm('确定删除该下载记录吗？')) return
  try {
    await api.deleteMvTask(id)
    await fetchTasks()
  } catch (e) {
    alert('删除失败: ' + e.message)
  }
}

const showErrorDetail = (task) => {
  selectedErrorTask.value = task
  errorDetailOpen.value = true
}

const copyPath = (path) => {
  if (!path) return
  navigator.clipboard?.writeText(path)
  alert('已复制文件路径到剪贴板:\n' + path)
}

const retryFromModal = async () => {
  if (!selectedErrorTask.value) return
  const id = selectedErrorTask.value.id
  errorDetailOpen.value = false
  await retryTask(id)
}

const providerName = (p) => {
  if (p === 'NETEASE') return '网易云'
  if (p === 'BILIBILI') return 'B站'
  return p
}

const statusName = (s) => {
  const map = {
    PENDING: '排队中',
    DOWNLOADING: '下载中',
    MERGING: '合流中',
    IMPORTING: '入库中',
    COMPLETED: '已入库',
    FAILED: '失败',
    CANCELLED: '已取消'
  }
  return map[s] || s
}

const formatDuration = (ms) => {
  if (!ms || ms <= 0) return '00:00'
  const s = Math.floor(ms / 1000)
  const min = Math.floor(s / 60)
  const sec = s % 60
  return String(min).padStart(2, '0') + ':' + String(sec).padStart(2, '0')
}

const formatBytes = (bytes) => {
  if (!bytes || bytes <= 0) return '0 B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

const formatSpeed = (bps) => {
  if (!bps || bps <= 0) return '—'
  if (bps < 1024 * 1024) return (bps / 1024).toFixed(1) + ' KB/s'
  return (bps / (1024 * 1024)).toFixed(2) + ' MB/s'
}

const formatTime = (iso) => {
  if (!iso) return '—'
  const d = new Date(iso)
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

onMounted(() => {
  fetchTasks()
  fetchBiliStatus()
  fetchGlobalSettings()
  timer = setInterval(fetchTasks, 3000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
  stopQrPolling()
})
</script>

<style scoped>
/* ===== 页面顶栏卡片 ===== */
.header-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 20px 24px;
  margin-bottom: 18px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.04);
}
.header-main {
  display: flex;
  align-items: center;
  gap: 16px;
}
.header-icon-box {
  width: 48px;
  height: 48px;
  background: linear-gradient(135deg, #eff6ff, #dbeafe);
  border: 1px solid #bfdbfe;
  border-radius: 10px;
  display: grid;
  place-items: center;
  font-size: 24px;
  flex-shrink: 0;
}
.header-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.header-title-row h1 {
  font-size: 20px;
  font-weight: 700;
  color: #0f172a;
  margin: 0;
}
.version-tag {
  background: #f1f5f9;
  color: #475569;
  border: 1px solid #cbd5e1;
  border-radius: 9999px;
  font-size: 11px;
  padding: 1px 8px;
  font-weight: 600;
}
.header-main p {
  color: #64748b;
  font-size: 13px;
  margin-top: 4px;
}
.header-meta {
  display: flex;
  gap: 12px;
}
.stat-pill {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-width: 80px;
  padding: 8px 14px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
}
.stat-pill.active {
  background: #eff6ff;
  border-color: #bfdbfe;
}
.stat-num {
  font-size: 18px;
  font-weight: 700;
  color: #0f172a;
  line-height: 1.1;
}
.stat-pill.active .stat-num { color: #2563eb; }
.stat-label {
  font-size: 11px;
  color: #64748b;
  margin-top: 4px;
}

/* ===== 搜索控制区域 ===== */
.search-section {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 18px 20px;
  margin-bottom: 20px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.04);
}
.search-bar-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.search-input-wrapper {
  position: relative;
  flex: 1;
  display: flex;
  align-items: center;
}
.search-icon {
  position: absolute;
  left: 14px;
  color: #94a3b8;
  pointer-events: none;
}
.search-input-wrapper input {
  width: 100%;
  height: 44px;
  padding: 0 38px 0 42px;
  background: #f8fafc;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  font-size: 14px;
  color: #0f172a;
  transition: all 0.2s;
}
.search-input-wrapper input:focus {
  background: #ffffff;
  border-color: #3b82f6;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.15);
  outline: none;
}
.clear-input-btn {
  position: absolute;
  right: 12px;
  width: 20px;
  height: 20px;
  display: grid;
  place-items: center;
  color: #94a3b8;
  border-radius: 50%;
  border: none;
  background: none;
  cursor: pointer;
}
.clear-input-btn:hover { color: #475569; background: #e2e8f0; }

.platform-tabs {
  display: flex;
  background: #f1f5f9;
  padding: 4px;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
}
.tab-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 14px;
  height: 36px;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 500;
  color: #64748b;
  border: none;
  background: transparent;
  cursor: pointer;
  transition: all 0.2s;
  white-space: nowrap;
}
.tab-btn.active {
  background: #ffffff;
  color: #0f172a;
  font-weight: 600;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.08);
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}
.dot.red { background: #e11d48; }
.dot.blue { background: #0284c7; }

.search-submit-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 44px;
  padding: 0 22px;
  background: linear-gradient(135deg, #2563eb, #1d4ed8);
  color: #ffffff;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
  box-shadow: 0 2px 6px rgba(37, 99, 235, 0.25);
  white-space: nowrap;
}
.search-submit-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.35);
}
.search-submit-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
  transform: none;
}

.preferences-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid #f1f5f9;
}
.pref-tip {
  font-size: 12px;
  color: #94a3b8;
}

.pref-switches {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-left: auto;
}

.pref-toggle-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #475569;
  cursor: pointer;
  user-select: none;
}

.pref-toggle-label input[type="checkbox"] {
  cursor: pointer;
  accent-color: #a21caf;
}

.pref-toggle-text {
  font-weight: 500;
}

.pref-toggle-badge {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 9999px;
  font-weight: 600;
}

.pref-toggle-badge.on {
  background: #fdf4ff;
  color: #a21caf;
  border: 1px solid #f5d0fe;
}

.pref-toggle-badge.off {
  background: #f1f5f9;
  color: #64748b;
  border: 1px solid #e2e8f0;
}

/* ===== 搜索结果网格 ===== */
.results-section {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 20px;
  margin-bottom: 24px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.04);
}
.section-title-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 14px;
  border-bottom: 1px solid #f1f5f9;
  margin-bottom: 18px;
}
.title-left {
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.title-left h2 {
  font-size: 16px;
  font-weight: 700;
  color: #0f172a;
  margin: 0;
}
.result-count {
  font-size: 12px;
  color: #64748b;
}
.searching-badge {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #2563eb;
}
.spinning {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}

.mv-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(270px, 1fr));
  gap: 18px;
}
.mv-card {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  transition: all 0.25s cubic-bezier(0.16, 1, 0.3, 1);
}
.mv-card:hover {
  transform: translateY(-3px);
  border-color: #cbd5e1;
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.08);
}
.mv-cover-box {
  position: relative;
  width: 100%;
  padding-top: 56.25%; /* 16:9 比例 */
  background: #0f172a;
  overflow: hidden;
}
.mv-cover {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.4s ease;
}
.mv-card:hover .mv-cover {
  transform: scale(1.04);
}
.mv-cover-fallback {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  color: #64748b;
}
.provider-tag {
  position: absolute;
  top: 8px;
  left: 8px;
  padding: 3px 8px;
  border-radius: 5px;
  font-size: 11px;
  font-weight: 700;
  color: #ffffff;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.3);
}
.provider-tag.netease { background: #e11d48; }
.provider-tag.bilibili { background: #0284c7; }
.duration-tag {
  position: absolute;
  bottom: 8px;
  right: 8px;
  padding: 2px 7px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 500;
  background: rgba(15, 23, 42, 0.78);
  backdrop-filter: blur(4px);
  color: #ffffff;
}
.mv-info {
  padding: 12px 14px;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.mv-title {
  font-size: 14px;
  font-weight: 600;
  line-height: 1.4;
  color: #0f172a;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  margin: 0;
}
.mv-author {
  display: flex;
  align-items: center;
  font-size: 12px;
  color: #64748b;
}
.author-label { color: #94a3b8; }
.author-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mv-card-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px 12px;
  border-top: 1px solid #f1f5f9;
  background: #fafbfc;
}
.source-link {
  font-size: 12px;
  color: #64748b;
  text-decoration: none;
  transition: color 0.15s;
}
.source-link:hover { color: #2563eb; }
.download-action-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 32px;
  padding: 0 13px;
  background: #2563eb;
  color: #ffffff;
  border: none;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}
.download-action-btn:hover:not(:disabled) {
  background: #1d4ed8;
}
.download-action-btn.downloaded-btn {
  background: #f0fdf4;
  color: #16a34a;
  border: 1px solid #bbf7d0;
  cursor: default;
}
.empty-results-box {
  text-align: center;
  padding: 48px 20px;
  color: #64748b;
}
.empty-icon { font-size: 36px; margin-bottom: 10px; }
.empty-results-box h3 { font-size: 16px; color: #1e293b; margin-bottom: 6px; }
.empty-results-box p { font-size: 13px; color: #94a3b8; }

/* ===== 任务管理看板 ===== */
.tasks-section {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.04);
}
.tasks-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid #e2e8f0;
  background: #fafbfc;
}
.tasks-header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}
.tasks-header-left h2 {
  font-size: 16px;
  font-weight: 700;
  color: #0f172a;
  margin: 0;
}
.task-filter-chips {
  display: flex;
  gap: 6px;
}
.chip-btn {
  height: 28px;
  padding: 0 10px;
  border-radius: 6px;
  font-size: 12px;
  color: #64748b;
  border: 1px solid #e2e8f0;
  background: #ffffff;
  cursor: pointer;
  transition: all 0.15s;
}
.chip-btn.active {
  color: #2563eb;
  background: #eff6ff;
  border-color: #bfdbfe;
  font-weight: 600;
}
.refresh-tasks-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 12px;
  background: #ffffff;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  color: #475569;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
}
.refresh-tasks-btn:hover:not(:disabled) {
  background: #f8fafc;
  color: #0f172a;
}

/* ===== 表格样式 ===== */
.table-container {
  overflow-x: auto;
}
.modern-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  text-align: left;
}
.modern-table th {
  background: #f8fafc;
  color: #64748b;
  font-weight: 600;
  padding: 12px 16px;
  border-bottom: 1px solid #e2e8f0;
  font-size: 12px;
  white-space: nowrap;
}
.modern-table td {
  padding: 14px 16px;
  border-bottom: 1px solid #f1f5f9;
  color: #334155;
  vertical-align: middle;
}
.task-row:hover td {
  background: #f8fafc;
}
.task-id-cell {
  font-family: ui-monospace, monospace;
  font-weight: 600;
  color: #64748b;
}
.title-cell {
  min-width: 260px;
  max-width: 360px;
}
.title-wrapper {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.row-title {
  font-size: 13px;
  color: #0f172a;
  line-height: 1.35;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row-artist {
  font-size: 12px;
  color: #64748b;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row-path {
  font-size: 11px;
  color: #94a3b8;
  font-family: ui-monospace, monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}
.row-path:hover {
  color: #2563eb;
  text-decoration: underline;
}
.provider-badge {
  display: inline-block;
  padding: 2px 7px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
}
.provider-badge.netease { background: #fff1f2; color: #e11d48; border: 1px solid #fecdd3; }
.provider-badge.bilibili { background: #f0f9ff; color: #0284c7; border: 1px solid #bae6fd; }

.progress-cell {
  min-width: 190px;
}
.status-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.status-badge {
  display: inline-block;
  padding: 2px 7px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
}
.status-badge.pending { background: #f8fafc; color: #64748b; border: 1px solid #e2e8f0; }
.status-badge.downloading { background: #eff6ff; color: #1d4ed8; border: 1px solid #bfdbfe; }
.status-badge.merging { background: #fefce8; color: #a16207; border: 1px solid #fef08a; }
.status-badge.importing { background: #f5f3ff; color: #6d28d9; border: 1px solid #ddd6fe; }
.status-badge.completed { background: #f0fdf4; color: #15803d; border: 1px solid #bbf7d0; }
.status-badge.failed { background: #fef2f2; color: #b91c1c; border: 1px solid #fecaca; }
.status-badge.cancelled { background: #f1f5f9; color: #64748b; }

.convert-dual-badge {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
  background: #fdf4ff;
  color: #a21caf;
  border: 1px solid #f0abfc;
}

.percent-text {
  font-size: 12px;
  font-weight: 600;
  color: #0f172a;
}
.error-tag-btn {
  padding: 1px 6px;
  border-radius: 4px;
  background: #fee2e2;
  color: #b91c1c;
  border: 1px solid #fca5a5;
  font-size: 10px;
  font-weight: 600;
  cursor: pointer;
}
.error-tag-btn:hover { background: #fecaca; }

.progress-track {
  height: 6px;
  background: #e2e8f0;
  border-radius: 3px;
  overflow: hidden;
}
.progress-fill {
  height: 100%;
  background: #2563eb;
  border-radius: 3px;
  transition: width 0.3s ease;
}
.progress-fill.merging { background: #ca8a04; }
.progress-fill.importing { background: #7c3aed; }
.progress-fill.completed { background: #16a34a; }
.progress-fill.failed { background: #dc2626; }

.speed-cell, .time-cell {
  white-space: nowrap;
  font-size: 12px;
  color: #475569;
}
.speed-val {
  font-weight: 600;
  color: #0f172a;
}
.size-cell {
  white-space: nowrap;
  font-size: 12px;
}
.size-wrapper {
  display: flex;
  align-items: baseline;
  gap: 3px;
}
.size-main { font-weight: 600; color: #0f172a; }
.size-total { color: #94a3b8; }

.actions-cell {
  text-align: right;
  white-space: nowrap;
}
.actions-group {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
}
.act-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 28px;
  padding: 0 10px;
  border-radius: 5px;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
  border: 1px solid transparent;
}
.act-btn.cancel {
  background: #fef2f2;
  color: #b91c1c;
  border-color: #fecaca;
}
.act-btn.cancel:hover { background: #fee2e2; }
.act-btn.retry {
  background: #eff6ff;
  color: #1d4ed8;
  border-color: #bfdbfe;
}
.act-btn.retry:hover { background: #dbeafe; }
.act-btn.delete {
  background: #ffffff;
  color: #94a3b8;
  border-color: #cbd5e1;
  padding: 0 8px;
}
.act-btn.delete:hover {
  color: #dc2626;
  border-color: #fca5a5;
  background: #fef2f2;
}
.empty-table-cell {
  text-align: center;
  padding: 40px;
  color: #94a3b8;
}

/* ===== 异常诊断弹窗 ===== */
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.5);
  backdrop-filter: blur(4px);
  display: grid;
  place-items: center;
  z-index: 1000;
  padding: 20px;
}
.detail-modal {
  width: min(600px, 100%);
  background: #ffffff;
  border-radius: 12px;
  box-shadow: 0 20px 40px rgba(15, 23, 42, 0.2);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.detail-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid #e2e8f0;
  background: #fafbfc;
}
.detail-title {
  display: flex;
  align-items: center;
  gap: 8px;
}
.warn-icon { color: #dc2626; }
.detail-title h3 {
  font-size: 16px;
  font-weight: 700;
  color: #0f172a;
  margin: 0;
}
.modal-close {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border-radius: 6px;
  border: none;
  background: none;
  color: #94a3b8;
  font-size: 20px;
  cursor: pointer;
}
.modal-close:hover { background: #e2e8f0; color: #334155; }
.detail-body {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-height: 65vh;
  overflow-y: auto;
}
.info-row {
  font-size: 13px;
}
.info-k { color: #64748b; }
.info-v { color: #0f172a; }
.error-box {
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 8px;
  padding: 12px;
}
.error-box-title {
  font-size: 12px;
  font-weight: 700;
  color: #b91c1c;
  margin-bottom: 6px;
}
.error-code-block {
  margin: 0;
  font-size: 11px;
  font-family: ui-monospace, monospace;
  color: #991b1b;
  white-space: pre-wrap;
  word-break: break-all;
  line-height: 1.5;
}
.troubleshoot-box {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 12px;
}
.troubleshoot-box h4 {
  margin: 0 0 8px;
  font-size: 12px;
  color: #0f172a;
}
.troubleshoot-box ul {
  margin: 0;
  padding-left: 18px;
  font-size: 12px;
  color: #475569;
  line-height: 1.6;
}
.detail-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  padding: 14px 20px;
  border-top: 1px solid #e2e8f0;
  background: #f1f5f9;
}
.btn-ghost {
  height: 36px;
  padding: 0 16px;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  background: #ffffff;
  color: #334155;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
}
.btn-primary {
  height: 36px;
  padding: 0 18px;
  border: none;
  border-radius: 6px;
  background: #2563eb;
  color: #ffffff;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

/* ===== B 站认证状态卡片 ===== */
.bili-auth-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  background: #f0f9ff;
  border: 1px solid #bae6fd;
  border-radius: 12px;
  padding: 14px 20px;
  margin-bottom: 18px;
  transition: all 0.2s ease;
}
.bili-auth-banner.is-logged-in {
  background: #f8fafc;
  border-color: #cbd5e1;
}
.bili-auth-main {
  display: flex;
  align-items: center;
  gap: 14px;
  flex: 1;
}
.bili-brand-icon {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: #00aeec;
  color: #ffffff;
  display: grid;
  place-items: center;
  flex-shrink: 0;
}
.bili-profile-info {
  display: flex;
  align-items: center;
  gap: 12px;
}
.bili-avatar {
  width: 42px;
  height: 42px;
  border-radius: 50%;
  border: 2px solid #00aeec;
  object-fit: cover;
  flex-shrink: 0;
}
.bili-text-wrap {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.bili-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.bili-uname {
  font-size: 15px;
  font-weight: 700;
  color: #0f172a;
}
.bili-vip-badge {
  font-size: 11px;
  font-weight: 600;
  padding: 1px 8px;
  border-radius: 9999px;
  background: #f1f5f9;
  color: #64748b;
  border: 1px solid #e2e8f0;
}
.bili-vip-badge.is-vip {
  background: linear-gradient(135deg, #fb7299, #ff5c8a);
  color: #ffffff;
  border: none;
}
.bili-security-badge {
  font-size: 11px;
  color: #059669;
  background: #ecfdf5;
  border: 1px solid #a7f3d0;
  padding: 1px 8px;
  border-radius: 4px;
  font-weight: 500;
}
.bili-status-sub {
  margin: 0;
  font-size: 12px;
  color: #64748b;
  line-height: 1.4;
}
.bili-unlogin-info {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.bili-unlogin-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.bili-unlogin-title {
  font-size: 14px;
  font-weight: 700;
  color: #0369a1;
}
.bili-warn-tag {
  font-size: 11px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 4px;
  background: #fffbeb;
  color: #b45309;
  border: 1px solid #fde68a;
}
.bili-unlogin-sub {
  margin: 0;
  font-size: 12px;
  color: #0284c7;
  line-height: 1.4;
}
.bili-auth-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.bili-primary-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 36px;
  padding: 0 16px;
  background: linear-gradient(135deg, #00aeec, #009cd6);
  color: #ffffff;
  border: none;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  box-shadow: 0 2px 6px rgba(0, 174, 236, 0.25);
  transition: all 0.2s ease;
}
.bili-primary-btn:hover {
  background: linear-gradient(135deg, #009cd6, #0088ba);
  transform: translateY(-1px);
}
.bili-action-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 32px;
  padding: 0 12px;
  background: #ffffff;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
  color: #475569;
  cursor: pointer;
  transition: all 0.15s ease;
}
.bili-action-btn:hover:not(:disabled) {
  border-color: #94a3b8;
  color: #1e293b;
  background: #f8fafc;
}
.bili-action-btn.danger {
  color: #dc2626;
  border-color: #fecaca;
  background: #fff5f5;
}
.bili-action-btn.danger:hover {
  background: #fee2e2;
  border-color: #fca5a5;
}

/* ===== 扫码弹窗卡片 ===== */
.qr-modal-card {
  width: min(440px, 100%);
  background: #ffffff;
  border-radius: 16px;
  box-shadow: 0 25px 50px -12px rgba(15, 23, 42, 0.25);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.qr-modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid #f1f5f9;
  background: #fafbfc;
}
.qr-header-left {
  display: flex;
  align-items: center;
  gap: 10px;
}
.qr-bili-logo {
  color: #00aeec;
  display: grid;
  place-items: center;
}
.qr-header-left h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: #0f172a;
}
.qr-modal-body {
  padding: 24px 20px;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: 16px;
}
.qr-canvas-box {
  width: 230px;
  height: 230px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  position: relative;
  overflow: hidden;
  display: grid;
  place-items: center;
}
.qr-loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: #64748b;
  padding: 20px;
}
.qr-img-wrapper {
  position: relative;
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
}
.qr-img {
  width: 210px;
  height: 210px;
  display: block;
}
.qr-mask {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 16px;
}
.qr-mask.expired {
  background: rgba(15, 23, 42, 0.85);
  color: #ffffff;
  font-size: 13px;
  font-weight: 600;
}
.qr-refresh-btn {
  border: none;
  background: #00aeec;
  color: #ffffff;
  padding: 6px 14px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s ease;
}
.qr-refresh-btn:hover {
  background: #009cd6;
}
.qr-mask.scanned {
  background: rgba(15, 23, 42, 0.75);
  color: #38bdf8;
  font-size: 13px;
  font-weight: 600;
}
.qr-mask.success {
  background: rgba(16, 185, 129, 0.9);
  color: #ffffff;
  font-size: 14px;
  font-weight: 700;
}
.qr-status-indicator {
  font-size: 14px;
  color: #334155;
}
.qr-status-indicator p {
  margin: 0;
}
.qr-status-indicator .scanned-tip {
  color: #0284c7;
  font-weight: 600;
}
.qr-status-indicator .success-tip {
  color: #16a34a;
  font-weight: 700;
}
.qr-status-indicator .expired-tip {
  color: #dc2626;
  font-weight: 600;
}
.qr-security-note {
  background: #f8fafc;
  border: 1px solid #f1f5f9;
  border-radius: 8px;
  padding: 10px 14px;
  text-align: left;
}
.qr-security-note p {
  margin: 0;
  font-size: 11px;
  color: #64748b;
  line-height: 1.5;
}
.qr-modal-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  padding: 12px 20px;
  border-top: 1px solid #f1f5f9;
  background: #fafbfc;
}

/* ===== B 站合集选集下载样式 ===== */
.card-action-group {
  display: flex;
  align-items: center;
  gap: 8px;
}
.parts-trigger-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 10px;
  font-size: 11px;
  font-weight: 600;
  color: #0284c7;
  background: #f0f9ff;
  border: 1px solid #bae6fd;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s ease;
  white-space: nowrap;
}
.parts-trigger-btn:hover {
  background: #e0f2fe;
  border-color: #38bdf8;
  color: #0369a1;
  transform: translateY(-1px);
}
.parts-modal-card {
  width: 100%;
  max-width: 760px;
  max-height: 86vh;
  background: #ffffff;
  border-radius: 16px;
  box-shadow: 0 20px 40px rgba(0, 0, 0, 0.25);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  animation: modalScaleIn 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}
.parts-modal-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 18px 24px 14px;
  border-bottom: 1px solid #e2e8f0;
  background: #fafbfc;
}
.parts-header-info {
  flex: 1;
  min-width: 0;
}
.parts-badge-title {
  display: flex;
  align-items: center;
  gap: 8px;
}
.provider-pill.bilibili {
  background: #0284c7;
  color: #ffffff;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 10px;
  font-weight: 700;
}
.parts-badge-title h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: #0f172a;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.parts-subtitle {
  margin: 4px 0 0;
  font-size: 12px;
  color: #64748b;
}
.parts-close-btn {
  background: none;
  border: none;
  color: #94a3b8;
  padding: 4px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s;
}
.parts-close-btn:hover {
  color: #0f172a;
  background: #f1f5f9;
}
.parts-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 24px;
  border-bottom: 1px solid #f1f5f9;
  background: #ffffff;
}
.parts-filter-wrap {
  position: relative;
  flex: 1;
  display: flex;
  align-items: center;
}
.filter-search-icon {
  position: absolute;
  left: 10px;
  color: #94a3b8;
}
.parts-filter-input {
  width: 100%;
  padding: 7px 28px 7px 30px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  font-size: 12px;
  color: #0f172a;
  background: #f8fafc;
  outline: none;
  transition: border-color 0.2s;
}
.parts-filter-input:focus {
  border-color: #38bdf8;
  background: #ffffff;
}
.parts-clear-btn {
  position: absolute;
  right: 8px;
  background: none;
  border: none;
  color: #94a3b8;
  padding: 2px;
  cursor: pointer;
}
.parts-shortcuts {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: nowrap;
}
.parts-btn-ghost {
  padding: 5px 9px;
  font-size: 11px;
  color: #475569;
  background: #f1f5f9;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.2s;
}
.parts-btn-ghost:hover {
  background: #e2e8f0;
  color: #0f172a;
}
.parts-options-row {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 8px 24px;
  background: #f8fafc;
  border-bottom: 1px solid #e2e8f0;
  font-size: 11px;
  color: #475569;
}
.parts-opt-label {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}
.parts-list-body {
  flex: 1;
  overflow-y: auto;
  padding: 12px 24px;
  min-height: 240px;
  max-height: 480px;
  background: #ffffff;
}
.parts-loading-box, .parts-empty-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 60px 20px;
  color: #94a3b8;
  font-size: 13px;
}
.parts-items-grid {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.part-item-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 9px 12px;
  border-radius: 8px;
  border: 1px solid #f1f5f9;
  background: #f8fafc;
  cursor: pointer;
  transition: all 0.15s ease;
}
.part-item-row:hover {
  background: #f0f9ff;
  border-color: #bae6fd;
}
.part-item-row.selected {
  background: #e0f2fe;
  border-color: #7dd3fc;
}
.part-check-col {
  display: flex;
  align-items: center;
}
.part-check-col input {
  cursor: pointer;
}
.part-page-badge {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 6px;
  border-radius: 4px;
  background: #bae6fd;
  color: #0369a1;
  min-width: 32px;
  text-align: center;
}
.part-info-col {
  flex: 1;
  min-width: 0;
}
.part-title-text {
  font-size: 13px;
  color: #1e293b;
  font-weight: 600;
  display: block;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.part-duration-text {
  font-size: 12px;
  color: #64748b;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.parts-modal-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 24px;
  border-top: 1px solid #e2e8f0;
  background: #fafbfc;
}
.parts-footer-left {
  font-size: 13px;
  color: #475569;
}
.parts-footer-left strong {
  color: #0284c7;
  font-size: 15px;
}
.parts-footer-right {
  display: flex;
  align-items: center;
  gap: 12px;
}
</style>
