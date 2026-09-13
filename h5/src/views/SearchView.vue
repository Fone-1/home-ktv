<template>
  <div class="page">
    <!-- 搜索框 / Search bar -->
    <div class="sec sbar">
      <button class="back" aria-label="返回" @click="$router.back()"><ChevronLeft :size="24" /></button>
      <div class="search grow">
        <Search :size="17" />
        <input ref="inp" v-model="kw" placeholder="歌名 / 歌手 / 拼音首字母" @input="onInput" @keyup.enter="handleEnter" />
        <button v-if="kw" class="clear" aria-label="清空" @click="clear"><X :size="16" /></button>
      </div>
    </div>

    <div class="sec filters" role="group" aria-label="歌曲版本筛选">
      <button v-for="filter in filters" :key="filter.value" class="filter"
              :class="{ on: activeFilter === filter.value }"
              :aria-pressed="activeFilter === filter.value"
              @click="selectFilter(filter.value)">{{ filter.label }}</button>
    </div>

    <!-- 搜索结果 / Search results -->
    <div class="sec grow results">
      <div v-if="loading" class="tip">搜索中…</div>
      <template v-else-if="results.length">
        <div class="cnt"><b>搜索结果</b><span>{{ results.length }} 首歌曲</span></div>
        <SongRow v-for="s in results" :key="s.id" :song="s" :keyword="kw"
                 :extra="fmtDur(s.durationMs)" :ordered="orderedIds.has(s.id)" @order="order" />
      </template>
      <div v-else-if="kw && !loading" class="empty">
        <div class="e-title">曲库还没有这首歌</div>
        <div class="empty-actions">
          <button class="btn primary-action" @click="openOnlineMv">全网搜索并点播 MV</button>
          <button class="btn ghost" @click="addWish">告诉我们想唱《{{ kw }}》</button>
        </div>
      </div>
      <!-- 未输入关键词：展示搜索历史与热门点歌推荐 / Empty query: Search history & hot recommendations -->
      <div v-else class="discovery">
        <!-- 搜索历史 -->
        <div v-if="searchHistory.length" class="disc-section">
          <div class="disc-head">
            <span>最近搜索</span>
            <button class="clear-hist-btn" title="清空搜索历史" @click="clearHistory">
              <Trash2 :size="13" /><span>清空</span>
            </button>
          </div>
          <div class="chip-wrap">
            <span v-for="item in searchHistory" :key="item" class="hist-chip" @click="applyTag(item)">
              {{ item }}
              <i class="del-one" title="删除" @click.stop="removeHistory(item)">×</i>
            </span>
          </div>
        </div>

        <!-- 大家都在搜推荐 -->
        <div class="disc-section">
          <div class="disc-head">
            <span class="hot-head"><Flame :size="15" class="flame-icon" />大家都在搜</span>
          </div>
          <div class="chip-wrap">
            <button v-for="tag in hotTags" :key="tag" class="hot-chip" @click="applyTag(tag)">
              {{ tag }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- 全网 MV 检索与点播弹层 / Online MV search & order modal -->
    <div v-if="onlineMvOpen" class="mv-modal-mask" @click.self="onlineMvOpen = false">
      <div class="mv-modal">
        <div class="mv-modal-header">
          <strong>全网 MV 搜索：《{{ kw }}》</strong>
          <button class="close-btn" @click="onlineMvOpen = false">×</button>
        </div>
        <div v-if="onlineMvLoading" class="mv-modal-loading">正在搜索网易云与 B 站视频…</div>
        <div v-else-if="onlineMvList.length" class="mv-modal-list">
          <div v-for="item in onlineMvList" :key="item.provider + item.externalId" class="mv-modal-item">
            <!-- 增加 referrerpolicy 解决防盗链，加载失败时优雅降级为占位图标 -->
            <img
              v-if="item.coverUrl && !isMvCoverFailed(item)"
              :src="item.coverUrl"
              class="item-cover"
              loading="lazy"
              referrerpolicy="no-referrer"
              @error="onMvCoverError(item)"
            />
            <div v-else class="item-cover fallback">🎬</div>
            <div class="item-info">
              <div class="item-title">{{ item.title }}</div>
              <div class="item-sub">
                <span class="item-badge" :class="item.provider.toLowerCase()">{{ item.provider === 'NETEASE' ? '网易云' : 'B站' }}</span>
                <span>{{ item.artist }}</span>
              </div>
            </div>
            <button class="order-mv-btn" :disabled="downloadingMap[item.provider + item.externalId]" @click="downloadAndOrder(item)">
              {{ downloadingMap[item.provider + item.externalId] ? '已提交' : '点播下载' }}
            </button>
          </div>
        </div>
        <div v-else class="mv-modal-empty">未搜索到相关 MV 视频</div>
      </div>
    </div>

    <TabBar active="home" />
  </div>
</template>

<script setup>
/**
 * SearchView - 歌曲搜索页面
 *
 * 支持按歌名、歌手或拼音首字母搜索歌曲。包含 300ms 输入防抖、
 * 搜索结果展示、点歌加入队列以及心愿歌曲提交功能。
 *
 * SearchView - Song search page.
 *
 * Supports searching songs by title, artist or pinyin initials.
 * Features 300ms input debounce, search result display, song queuing,
 * and wish-song submission.
 */
import { ref, onMounted, reactive } from 'vue'
import api, { makeControls } from '../api/client'
import { useUserStore } from '../stores/user'
import { useToast } from '../composables/useToast'
import TabBar from '../components/TabBar.vue'
import SongRow from '../components/SongRow.vue'
import { ChevronLeft, Search, X, Trash2, Flame } from 'lucide-vue-next'

const user = useUserStore()
const { toast } = useToast()
const controls = makeControls(user.clientToken)

/** @type {import('vue').Ref<string>} 当前搜索关键词 / Current search keyword */
const kw = ref('')
/** @type {import('vue').Ref<Array>} 搜索结果列表 / Search result list */
const results = ref([])
/** @type {import('vue').Ref<boolean>} 搜索加载状态 / Search loading flag */
const loading = ref(false)
const activeFilter = ref('')
const filters = [
  { label: '全部', value: '' },
  { label: 'KTV 双音轨', value: 'KTV_VIDEO' },
  { label: 'MV', value: 'MV' },
  { label: '纯音频', value: 'AUDIO' }
]
/** 已点歌 ID 集合，用于高亮标记 / Ordered song ID set, for highlight marking */
const orderedIds = reactive(new Set())
const inp = ref(null)
/** @type {number|null} 防抖定时器引用 / Debounce timer reference */
let debounce = null
let searchSequence = 0

/** 本地搜索历史记录 / Local search history */
const searchHistory = ref([])
/** 热门推荐点唱标签 / Hot recommended search tags */
const hotTags = ['周杰伦', '陈奕迅', '王菲', '邓紫棋', '海阔天空', '粤语金曲', '经典老歌', '情歌对唱', '抖音热歌']

const onlineMvOpen = ref(false)
const onlineMvLoading = ref(false)
const onlineMvList = ref([])
// 记录全网 MV 封面加载失败项，避免渲染异常
const failedMvCoverKeys = ref(new Set())
const onMvCoverError = (item) => {
  if (!item) return
  failedMvCoverKeys.value.add((item.provider || '') + '_' + (item.externalId || ''))
}
const isMvCoverFailed = (item) => {
  if (!item) return false
  return failedMvCoverKeys.value.has((item.provider || '') + '_' + (item.externalId || ''))
}
const downloadingMap = reactive({})

onMounted(() => {
  inp.value?.focus()
  try {
    const saved = localStorage.getItem('ktv_search_history')
    if (saved) searchHistory.value = JSON.parse(saved)
  } catch {
    searchHistory.value = []
  }
})

/**
 * 保存搜索关键词到本地历史
 * Save search keyword to local history
 * @param {string} query
 */
function saveHistory(query) {
  const q = query.trim()
  if (!q) return
  const list = searchHistory.value.filter(k => k.toLowerCase() !== q.toLowerCase())
  list.unshift(q)
  if (list.length > 10) list.length = 10
  searchHistory.value = list
  try { localStorage.setItem('ktv_search_history', JSON.stringify(list)) } catch {}
}

/** 清空所有搜索历史 / Clear all search history */
function clearHistory() {
  searchHistory.value = []
  try { localStorage.removeItem('ktv_search_history') } catch {}
}

/** 删除单个搜索历史项 / Remove single search history item */
function removeHistory(item) {
  searchHistory.value = searchHistory.value.filter(k => k !== item)
  try { localStorage.setItem('ktv_search_history', JSON.stringify(searchHistory.value)) } catch {}
}

/** 点击标签快捷填入并搜索 / Click tag to apply and search */
function applyTag(tag) {
  kw.value = tag
  saveHistory(tag)
  loading.value = true
  doSearch()
}

/** 回车直接触发搜索并记录历史 / Handle enter key */
function handleEnter() {
  if (debounce) clearTimeout(debounce)
  const q = kw.value.trim()
  if (!q) return
  saveHistory(q)
  loading.value = true
  doSearch()
}

/**
 * 输入事件处理，300ms 防抖触发搜索（详设 H5-03）
 *
 * Input event handler with 300ms debounce before search (spec H5-03).
 */
function onInput() {
  if (debounce) clearTimeout(debounce)
  const q = kw.value.trim()
  if (!q) { results.value = []; loading.value = false; return }
  loading.value = true
  debounce = setTimeout(() => {
    saveHistory(q)
    doSearch()
  }, 250)
}

/**
 * 执行歌曲搜索请求
 *
 * Execute song search API request.
 */
async function doSearch() {
  const q = kw.value.trim()
  if (!q) return
  const sequence = ++searchSequence
  try {
    const songs = await api.searchSongs(q, activeFilter.value)
    if (sequence === searchSequence) results.value = songs
  } catch {
    if (sequence === searchSequence) results.value = []
  } finally {
    if (sequence === searchSequence) loading.value = false
  }
}

async function openOnlineMv() {
  if (!kw.value.trim()) return
  onlineMvOpen.value = true
  onlineMvLoading.value = true
  failedMvCoverKeys.value.clear()
  try {
    const res = await api.searchMv(kw.value.trim(), 'ALL', 15)
    onlineMvList.value = res.items || []
  } catch (e) {
    toast('全网搜索失败: ' + (e.message || '网络异常'))
  } finally {
    onlineMvLoading.value = false
  }
}

async function downloadAndOrder(item) {
  const key = item.provider + item.externalId
  downloadingMap[key] = true
  try {
    await api.downloadMv({
      provider: item.provider,
      externalId: item.externalId,
      title: item.title,
      artist: item.artist,
      coverUrl: item.coverUrl,
      resolution: item.resolution,
      autoEnqueue: true
    })
    toast('已提交下载《' + item.title + '》，入库后自动为您点播！')
  } catch (e) {
    downloadingMap[key] = false
    toast(e.message || '提交下载失败')
  }
}

/** 清空搜索关键词和结果 / Clear keyword and results */
function clear() { searchSequence++; kw.value = ''; results.value = []; loading.value = false; inp.value?.focus() }

function selectFilter(value) {
  if (activeFilter.value === value) return
  activeFilter.value = value
  if (!kw.value.trim()) return
  if (debounce) clearTimeout(debounce)
  loading.value = true
  doSearch()
}

/**
 * 将歌曲加入点歌队列，并标记为已点。
 *
 * Queue a song for playback and mark it as ordered.
 *
 * @param {Object} song - 歌曲对象，需包含 id 属性 / Song object with an id property
 */
async function order(song) {
  try {
    await controls.order(song.id)
    orderedIds.add(song.id)
    toast('已加入队列')
  } catch (e) {
    toast(e.code === 'SONG_IN_QUEUE' ? (e.message || '已在队列中') : (e.message || '点歌失败'))
  }
}

/**
 * 提交心愿歌曲，通知管理端补充曲库。
 *
 * Submit a wish song request to notify admin to add the song.
 */
async function addWish() {
  try {
    await api.addWish(kw.value.trim(), user.clientToken)
    toast('已记下《' + kw.value.trim() + '》，稍后补充到曲库')
  } catch (e) {
    toast(e.message || '提交失败')
  }
}

/**
 * 将毫秒时长格式化为 m:ss 字符串。
 *
 * Format duration in milliseconds to m:ss string.
 *
 * @param {number} ms - 毫秒时长 / Duration in milliseconds
 * @returns {string} 格式化后的时长，如 "3:45" / Formatted duration, e.g. "3:45"
 */
function fmtDur(ms) {
  if (!ms) return ''
  const s = Math.round(ms / 1000)
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}
</script>

<style scoped>
.page { min-height: 100vh; padding-bottom: 74px; display: flex; flex-direction: column; }
.sec { padding: 0 16px; }
.sbar { display:flex;align-items:center;gap:8px;padding-top:10px; }
.back { width:30px;height:30px;display:grid;place-items:center;color:var(--dim);padding:0; }
.search {
  height:46px;background:var(--panel);border:1px solid rgba(255,198,75,.28);border-radius:8px;
  padding:0 12px;display:flex;align-items:center;gap:8px;color:var(--gold);
}
.search input { flex: 1; background: none; border: none; outline: none; color: var(--text); font-size: 14px; }
.clear { display:grid;place-items:center;color:var(--dim2);padding:4px; }
.filters { display:flex;gap:7px;padding-top:10px;overflow-x:auto;scrollbar-width:none; }.filters::-webkit-scrollbar { display:none; }.filter { flex:none;padding:6px 10px;border:1px solid var(--line);border-radius:999px;color:var(--dim);font-size:10px;line-height:1.2; }.filter.on { border-color:var(--coral);color:var(--coral);background:rgba(255,107,97,.08); }
.results { margin-top:6px;overflow-y:auto; }
.cnt { display:flex;justify-content:space-between;padding:5px 0 8px;color:var(--dim2);font-size:10px; }.cnt b { color:var(--text);font-size:12px; }
.tip { color: var(--dim2); font-size: 13px; padding: 30px 0; text-align: center; }
.discovery { padding: 18px 0; display: flex; flex-direction: column; gap: 20px; }
.disc-section { display: flex; flex-direction: column; gap: 10px; }
.disc-head { display: flex; align-items: center; justify-content: space-between; font-size: 12px; color: var(--dim); }
.disc-head .hot-head { display: flex; align-items: center; gap: 5px; color: var(--gold); font-weight: 700; }
.flame-icon { color: var(--coral); }
.clear-hist-btn { display: flex; align-items: center; gap: 4px; font-size: 11px; color: var(--dim2); padding: 2px 4px; }
.clear-hist-btn:active { color: var(--coral); }
.chip-wrap { display: flex; flex-wrap: wrap; gap: 8px; }
.hist-chip {
  display: inline-flex; align-items: center; gap: 6px; padding: 6px 12px;
  border-radius: 999px; background: var(--panel2); border: 1px solid var(--glass-border);
  font-size: 12px; color: var(--dim); cursor: pointer; transition: var(--transition);
}
.hist-chip:active { background: rgba(255,255,255,.1); }
.del-one { font-style: normal; color: var(--dim2); font-size: 14px; line-height: 1; padding: 0 2px; }
.del-one:hover { color: var(--coral); }
.hot-chip {
  padding: 6px 12px; border-radius: 999px; background: rgba(255,198,75,.08);
  border: 1px solid rgba(255,198,75,.2); font-size: 12px; color: var(--gold);
  cursor: pointer; transition: var(--transition);
}
.hot-chip:active { transform: scale(.95); background: rgba(255,198,75,.16); }
.empty { text-align: center; padding: 40px 0; }
.e-title { color: var(--dim); margin-bottom: 16px; }
.empty-actions { display: flex; flex-direction: column; align-items: center; gap: 10px; }
.primary-action {
  background: linear-gradient(135deg, #f59e0b, #ef4444);
  color: #fff;
  border: none;
  padding: 8px 18px;
  border-radius: 9999px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.mv-modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.65);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: flex-end;
  z-index: 1000;
}
.mv-modal {
  width: 100%;
  max-height: 75vh;
  background: var(--panel);
  border-radius: 16px 16px 0 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.mv-modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  border-bottom: 1px solid var(--line);
  color: var(--text);
}
.close-btn {
  font-size: 20px;
  color: var(--dim2);
  background: none;
  border: none;
  padding: 4px;
}
.mv-modal-loading, .mv-modal-empty {
  padding: 40px;
  text-align: center;
  color: var(--dim2);
  font-size: 13px;
}
.mv-modal-list {
  padding: 12px 16px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.mv-modal-item {
  display: flex;
  align-items: center;
  gap: 12px;
  background: rgba(255, 255, 255, 0.03);
  padding: 8px;
  border-radius: 8px;
}
.item-cover {
  width: 64px;
  height: 36px;
  border-radius: 4px;
  object-fit: cover;
}
.item-cover.fallback {
  background: #1e293b;
  display: grid;
  place-items: center;
  font-size: 18px;
}
.item-info {
  flex: 1;
  min-width: 0;
}
.item-title {
  font-size: 13px;
  color: var(--text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.item-sub {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--dim2);
  margin-top: 4px;
}
.item-badge {
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 9px;
  color: #fff;
}
.item-badge.netease { background: #e11d48; }
.item-badge.bilibili { background: #0284c7; }
.order-mv-btn {
  padding: 6px 12px;
  background: var(--gold);
  color: #1e1e2d;
  border: none;
  border-radius: 9999px;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
}
.order-mv-btn:disabled {
  background: var(--line);
  color: var(--dim2);
  cursor: not-allowed;
}
</style>
