<template>
  <div class="songrow">
    <!-- 排名区域 / Rank area -->
    <span v-if="rank" class="rank" :class="{ top: rank <= 3 }">{{ rank }}</span>
    <div class="cover" :class="{ empty: !song.coverUrl }" @click="openActionSheet">
      <img v-if="song.coverUrl" :src="song.coverUrl" :alt="`${song.title || '歌曲'}封面`" loading="lazy" referrerpolicy="no-referrer" />
      <Music2 v-else :size="18" />
    </div>
    <!-- 歌曲信息，点击唤出操作面板 / Song info, click to open action sheet -->
    <div class="grow info" @click="openActionSheet">
      <div class="t">
        <span v-html="highlightedTitle"></span>
        <span class="tag" :class="tagClass">{{ tagText }}</span>
      </div>
      <div class="s">{{ song.artist }}<span v-if="extra"> · {{ extra }}</span></div>
    </div>

    <!-- 快速加入歌单按钮 / Quick add to playlist -->
    <button class="playlist-btn" aria-label="加入歌单" title="加入歌单" @click="openPlaylistPicker"><ListPlus :size="18" /></button>

    <!-- 收藏按钮 / Favorite button -->
    <button class="favorite-btn" :class="{ on: favorites.has(song.id) }" :disabled="favoriteBusy"
            :aria-label="favorites.has(song.id) ? '取消收藏' : '收藏'" @click="toggleFavorite">
      <Heart :size="19" :fill="favorites.has(song.id) ? 'currentColor' : 'none'" />
    </button>

    <!-- 点歌/排队状态核心按钮 / Primary Order & Queue State Button -->
    <!-- ① 正在演唱中 / Currently singing on TV screen -->
    <div v-if="isCurrentlyPlaying" class="playing-chip" @click="toast('当前电视大屏正在演唱这首歌')">
      <div class="soundwave" aria-hidden="true"><span></span><span></span><span></span></div>
      <em>在唱</em>
    </div>
    <!-- ② 已在待播队列中 / Already in waiting queue -->
    <button v-else-if="queuePosition > 0" class="order-btn queued" title="点击调整播放顺序" @click="openQueueMenu">
      <span class="q-pos">第{{ queuePosition }}首</span>
    </button>
    <!-- ③ 外部已点标记兜底 / External ordered flag fallback -->
    <button v-else-if="ordered" class="order-btn done" disabled aria-label="已点"><Check :size="18" /></button>
    <!-- ④ 未点播：点击极速点歌 / Unordered: Tap to instant order -->
    <button v-else class="order-btn" aria-label="点歌" title="点歌（长按或点击歌曲可插播）" @click="doOrder(false)">
      <Plus :size="22" />
    </button>
  </div>

  <!-- 歌曲快捷详情与点歌操作面板 / Song Action Bottom Sheet -->
  <Teleport to="body">
    <div v-if="actionSheetOpen" class="action-mask" @click.self="actionSheetOpen = false">
      <section class="action-sheet" role="dialog" aria-modal="true" aria-label="歌曲点播操作">
        <header class="sheet-head">
          <div class="sheet-cover">
            <img v-if="song.coverUrl" :src="song.coverUrl" referrerpolicy="no-referrer" />
            <Music2 v-else :size="24" />
          </div>
          <div class="sheet-info">
            <strong>{{ song.title }}</strong>
            <p>{{ song.artist || '未知歌手' }} <span class="tag" :class="tagClass">{{ tagText }}</span></p>
          </div>
          <button class="sheet-close" aria-label="关闭" @click="actionSheetOpen = false">×</button>
        </header>
        <div class="sheet-actions">
          <button class="sheet-btn primary" @click="handleActionSheetOrder(false)">
            <Plus :size="18" /><span>点歌（排入队尾）</span>
          </button>
          <button class="sheet-btn gold" @click="handleActionSheetOrder(true)">
            <Zap :size="18" /><span>优先插播（设为下一首唱）</span>
          </button>
          <button class="sheet-btn" @click="handleActionSheetFavorite">
            <Heart :size="18" :fill="favorites.has(song.id) ? 'currentColor' : 'none'" />
            <span>{{ favorites.has(song.id) ? '取消收藏' : '加入收藏' }}</span>
          </button>
          <button class="sheet-btn" @click="handleActionSheetPlaylist">
            <ListPlus :size="18" /><span>加入歌单</span>
          </button>
        </div>
      </section>
    </div>
  </Teleport>

  <!-- 队列状态调整弹窗（已在队列时点击触发）/ Queue State Adjustment Dialog -->
  <Teleport to="body">
    <div v-if="queueMenuOpen" class="action-mask" @click.self="queueMenuOpen = false">
      <section class="action-sheet" role="dialog" aria-modal="true" aria-label="已点歌曲管理">
        <header class="sheet-head">
          <div class="sheet-info">
            <strong>《{{ song.title }}》已在待播列表中</strong>
            <p>当前排在第 {{ queuePosition }} 位<span v-if="queueItem?.orderedByNick">（由 {{ queueItem.orderedByNick }} 点播）</span></p>
          </div>
          <button class="sheet-close" aria-label="关闭" @click="queueMenuOpen = false">×</button>
        </header>
        <div class="sheet-actions">
          <button class="sheet-btn gold" @click="doTop">
            <Zap :size="18" /><span>设为下一首播放（插播顶歌）</span>
          </button>
          <button class="sheet-btn" @click="doReorder">
            <Plus :size="18" /><span>再点一次（加到队尾重唱）</span>
          </button>
          <button class="sheet-btn" @click="queueMenuOpen = false">
            <span>保持当前顺序</span>
          </button>
        </div>
      </section>
    </div>
  </Teleport>

  <!-- 歌单选择弹窗 / Playlist Picker Modal -->
  <Teleport to="body">
    <div v-if="playlistOpen" class="playlist-mask" @click.self="closePlaylistPicker">
      <section class="playlist-dialog" role="dialog" aria-modal="true" aria-label="加入歌单">
        <header class="playlist-dialog-head"><div><strong>加入歌单</strong><small>{{ song.title }} · {{ song.artist || '未知歌手' }}</small></div><button aria-label="关闭" @click="closePlaylistPicker">×</button></header>
        <div v-if="playlistLoading" class="playlist-dialog-empty">正在加载歌单…</div>
        <div v-else class="playlist-options">
          <button v-for="playlist in playlists" :key="playlist.id" :disabled="addingPlaylistId === playlist.id" @click="addToPlaylist(playlist)"><span><strong>{{ playlist.name }}</strong><small>{{ playlist.theme || '未设置主题' }} · {{ playlist.songCount || 0 }} 首</small></span><em>{{ addingPlaylistId === playlist.id ? '加入中…' : '加入' }}</em></button>
          <div v-if="!playlists.length" class="playlist-dialog-empty">暂无公开歌单</div>
        </div>
      </section>
    </div>
  </Teleport>
</template>

<script setup>
/**
 * SongRow 组件 —— 歌单列表行。
 * 支持排名展示、关键词高亮、媒体类型标签、大屏播放与排队状态感知、极速点歌与优先插播、收藏切换与加入歌单。
 *
 * SongRow component — a single row in a song list.
 * Supports rank display, keyword highlighting, media type tags, now-playing & queue
 * awareness, one-tap ordering & priority boosting, favorite toggling, and playlist management.
 */
import { computed, ref } from 'vue'
import api, { makeControls } from '../api/client'
import { useFavoritesStore } from '../stores/favorites'
import { useUserStore } from '../stores/user'
import { usePlayerStore } from '../stores/player'
import { useToast } from '../composables/useToast'
import { Check, Heart, ListPlus, Music2, Plus, Zap } from 'lucide-vue-next'

const props = defineProps({
  /** 歌曲对象，必传 / Song object, required */
  song: { type: Object, required: true },
  /** 排名序号，<=3 时高亮 / Rank number, highlighted when <= 3 */
  rank: { type: Number, default: 0 },
  /** 搜索关键词，用于标题高亮 / Search keyword for title highlighting */
  keyword: { type: String, default: '' },
  /** 附加信息文本，显示在歌手名后 / Extra text shown after artist name */
  extra: { type: String, default: '' },
  /** 是否已点歌，控制按钮状态 / Whether the song has already been ordered */
  ordered: { type: Boolean, default: false }
})

/** 触发点歌事件 / Emits order song event */
const emit = defineEmits(['order'])

const favorites = useFavoritesStore()
const user = useUserStore()
const player = usePlayerStore()
const { toast } = useToast()
const controls = makeControls(user.clientToken)

const favoriteBusy = ref(false)
const playlistOpen = ref(false)
const playlistLoading = ref(false)
const playlists = ref([])
const addingPlaylistId = ref(null)

const actionSheetOpen = ref(false)
const queueMenuOpen = ref(false)

/** 当前大屏是否正在演唱本曲目 / Whether this song is currently playing on the TV */
const isCurrentlyPlaying = computed(() => player.nowPlaying?.song?.id === props.song.id)

/** 当前曲目在排队队列中的索引 / Index in the waiting queue */
const queueIndex = computed(() => (player.queue || []).findIndex(item => item.song?.id === props.song.id))

/** 当前曲目在队列中的排位序号（1-based）/ 1-based position in waiting queue */
const queuePosition = computed(() => queueIndex.value >= 0 ? queueIndex.value + 1 : 0)

/** 对应的队列项对象 / Corresponding queue item */
const queueItem = computed(() => queueIndex.value >= 0 ? player.queue[queueIndex.value] : null)

/**
 * 执行点歌操作（支持普通点歌与优先插播）。
 * @param {boolean} priority - 是否优先插播为下一首
 * @param {boolean} force - 是否强制重复点歌
 *
 * Execute order song action (supports normal ordering and priority boosting).
 */
async function doOrder(priority = false, force = false) {
  try {
    const res = priority
      ? await controls.orderAndTop(props.song.id, force)
      : await controls.order(props.song.id, force)

    if (priority) {
      if (res?.playbackStarted || res?.playback_started) {
        toast(`已开启播放《${props.song.title}》！`)
      } else {
        const pos = res?.position ?? 1
        toast(pos === 1 ? `已将《${props.song.title}》设为下一首播放！` : `已将《${props.song.title}》优先插播至待唱第 ${pos} 首！`)
      }
    } else {
      const pos = res?.position ?? (player.queueCount || 1)
      toast(`已加入队列 · 待唱第 ${pos} 首`, {
        actionText: '设为下一首',
        onAction: async () => {
          const targetQueueId = res?.queue_id || res?.queueId || (player.queue || []).find(q => q.song?.id === props.song.id)?.queueId
          if (targetQueueId) {
            try {
              const topRes = await controls.top(targetQueueId)
              const finalPos = topRes?.position ?? 1
              toast(finalPos === 1 ? `已将《${props.song.title}》插播至下一首！` : `已将《${props.song.title}》插播至待唱第 ${finalPos} 首！`)
            } catch (err) {
              toast(err.message || '置顶失败，请重试')
            }
          } else {
            toast('未找到对应队列项，请在待唱列表中调整')
          }
        }
      })
    }
    emit('order', props.song)
  } catch (e) {
    if (e.code === 'SONG_IN_QUEUE') {
      openQueueMenu()
    } else {
      toast(e.message || '点歌失败')
    }
  }
}

/** 打开歌曲操作底栏 / Open song action sheet */
function openActionSheet() {
  actionSheetOpen.value = true
}

/** 底栏点歌触发 / Order from action sheet */
async function handleActionSheetOrder(priority) {
  actionSheetOpen.value = false
  await doOrder(priority)
}

/** 底栏收藏触发 / Favorite from action sheet */
async function handleActionSheetFavorite() {
  await toggleFavorite()
}

/** 底栏歌单触发 / Playlist from action sheet */
function handleActionSheetPlaylist() {
  actionSheetOpen.value = false
  openPlaylistPicker()
}

/** 打开已排队歌曲管理浮层 / Open queue management menu */
function openQueueMenu() {
  queueMenuOpen.value = true
}

/** 将已在队列中的歌曲顶到下一首 / Boost queued song to play next */
async function doTop() {
  queueMenuOpen.value = false
  if (!queueItem.value?.queueId) {
    toast('未找到队列项')
    return
  }
  try {
    await controls.top(queueItem.value.queueId)
    toast(`《${props.song.title}》已置顶为下一首！`)
  } catch (e) {
    toast(e.message || '置顶失败')
  }
}

/** 重复点播已在队列中的歌曲 / Force re-order queued song */
async function doReorder() {
  queueMenuOpen.value = false
  await doOrder(false, true)
}

/**
 * 切换当前歌曲的收藏状态，并弹出提示。
 *
 * Toggle the favorite state of the current song and show a toast notification.
 */
async function toggleFavorite() {
  favoriteBusy.value = true
  try {
    const added = await favorites.toggle(props.song.id, user.clientToken)
    toast(added ? '已加入收藏' : '已取消收藏')
  } catch (error) {
    toast(error.message || '收藏操作失败')
  } finally {
    favoriteBusy.value = false
  }
}

async function openPlaylistPicker() {
  playlistOpen.value = true
  if (playlists.value.length || playlistLoading.value) return
  playlistLoading.value = true
  try { playlists.value = await api.playlists() } catch (error) { toast(error.message || '歌单加载失败') } finally { playlistLoading.value = false }
}
function closePlaylistPicker() { if (!addingPlaylistId.value) playlistOpen.value = false }
async function addToPlaylist(playlist) {
  addingPlaylistId.value = playlist.id
  try {
    const result = await api.addSongToPlaylist(playlist.id, props.song.id)
    playlist.songCount = result?.songCount ?? playlist.songCount
    toast(result?.added === false ? '歌曲已在歌单中' : `已加入「${playlist.name}」`)
    playlistOpen.value = false
  } catch (error) { toast(error.message || '加入歌单失败') } finally { addingPlaylistId.value = null }
}

const tagText = computed(() => ({
  KTV_VIDEO: 'KTV版', MV: 'MV版', AUDIO: '音频版'
}[props.song.mediaType] || ''))

const tagClass = computed(() => ({
  KTV_VIDEO: 'tag-ktv', MV: 'tag-mv', AUDIO: 'tag-audio'
}[props.song.mediaType] || 'tag-audio'))

/**
 * 关键词高亮（详设 H5-03）。
 * 将歌曲标题中匹配关键词的部分用高亮 span 包裹。
 *
 * Keyword highlighting (design spec H5-03).
 * Wraps the matching portion of the song title in a highlighted span.
 */
const highlightedTitle = computed(() => {
  const title = props.song.title || ''
  const kw = props.keyword?.trim()
  if (!kw) return escapeHtml(title)
  const idx = title.toLowerCase().indexOf(kw.toLowerCase())
  if (idx < 0) return escapeHtml(title)
  return escapeHtml(title.slice(0, idx))
    + '<span class="hl">' + escapeHtml(title.slice(idx, idx + kw.length)) + '</span>'
    + escapeHtml(title.slice(idx + kw.length))
})

/**
 * HTML 转义，防止 XSS 注入。
 * 转义 & < > " 四个字符。
 *
 * Escape HTML special characters to prevent XSS injection.
 * Escapes & < > " characters.
 * @param {string} s - 原始字符串 / Raw string
 * @returns {string} 转义后的字符串 / Escaped string
 */
function escapeHtml(s) {
  return s.replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]))
}
</script>

<style scoped>
.songrow { display:flex;align-items:center;gap:9px;min-height:62px;padding:7px 0;border-bottom:1px solid rgba(255,255,255,.07); }
.rank { width:24px;text-align:center;font-weight:800;color:var(--dim2);font-size:12px; }
.rank.top { color: var(--gold); }
.cover { width:46px;height:46px;display:grid;place-items:center;flex:none;overflow:hidden;border-radius:8px;background:#202630 center/cover no-repeat;color:var(--dim2);border:1px solid rgba(255,255,255,.08);cursor:pointer; }
.cover img { width:100%;height:100%;object-fit:cover;display:block; }
.info { min-width: 0; cursor: pointer; }
.t { font-size:13px;font-weight:650;display:flex;align-items:center;gap:5px; }
.t :deep(.hl) { color: var(--gold); }
.s { font-size:10px;color:var(--dim);margin-top:4px; }
.order-btn {
  width:42px;height:42px;display:grid;place-items:center;background:var(--gold);color:#201a0f;
  border-radius:50%;padding:0;flex:none;transition:var(--transition);
  box-shadow: 0 4px 14px rgba(255,198,75,.28);
}
.order-btn:active { transform: scale(.92); }
.order-btn.done { background:rgba(110,214,168,.12);color:var(--mint);box-shadow:none; }
.order-btn.queued {
  width: auto; min-width: 58px; height: 32px; padding: 0 10px; border-radius: 999px;
  background: rgba(110,214,168,.15); border: 1px solid rgba(110,214,168,.4);
  color: var(--mint); box-shadow: none; font-size: 11px; font-weight: 700;
}
.playing-chip {
  display: inline-flex; align-items: center; gap: 5px; height: 32px; padding: 0 10px;
  border-radius: 999px; background: rgba(52,211,153,.16); border: 1px solid rgba(52,211,153,.45);
  color: var(--green); font-size: 11px; font-weight: 700; cursor: pointer;
}
.playing-chip em { font-style: normal; }
.soundwave { display: inline-flex; align-items: flex-end; gap: 2px; height: 12px; }
.soundwave span {
  width: 2.5px; height: 100%; background: var(--green); border-radius: 1px;
  animation: wave 0.8s ease-in-out infinite alternate;
}
.soundwave span:nth-child(2) { animation-delay: 0.25s; }
.soundwave span:nth-child(3) { animation-delay: 0.5s; }
@keyframes wave {
  0% { height: 25%; }
  100% { height: 100%; }
}

.favorite-btn { display:grid;place-items:center;color:var(--dim2);padding:6px;flex:none;transition:var(--transition); }
.playlist-btn { display:grid;place-items:center;color:var(--dim2);padding:6px;flex:none;transition:var(--transition); }
.playlist-btn:active { transform: scale(.88); }
.favorite-btn.on { color:var(--coral); }
.favorite-btn:active { transform: scale(.88); }
.favorite-btn:disabled { opacity: .45; }

/* 操作抽屉底栏 / Action Bottom Sheet */
.action-mask {
  position: fixed; inset: 0; z-index: 130;
  background: rgba(0,0,0,.68); backdrop-filter: blur(8px);
  display: flex; align-items: flex-end; justify-content: center;
}
.action-sheet {
  width: 100%; max-width: 480px; background: #171c24;
  border-top: 1px solid rgba(255,255,255,.12); border-radius: 20px 20px 0 0;
  padding: 18px 20px calc(20px + var(--safe-bottom)); color: #f7f3eb;
  box-shadow: 0 -10px 40px rgba(0,0,0,.6);
  animation: slideUp .2s cubic-bezier(.2,0,0,1);
}
@keyframes slideUp { from { transform: translateY(100%); } to { transform: translateY(0); } }
.sheet-head { display: flex; align-items: center; gap: 12px; padding-bottom: 16px; border-bottom: 1px solid rgba(255,255,255,.08); }
.sheet-cover { width: 50px; height: 50px; border-radius: 10px; overflow: hidden; background: #222936; display: grid; place-items: center; flex: none; border: 1px solid var(--glass-border); }
.sheet-cover img { width: 100%; height: 100%; object-fit: cover; }
.sheet-info { flex: 1; min-width: 0; }
.sheet-info strong { display: block; font-size: 15px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sheet-info p { margin-top: 4px; font-size: 11px; color: var(--dim); display: flex; align-items: center; gap: 6px; }
.sheet-close { font-size: 26px; color: var(--dim2); padding: 0 4px; line-height: 1; }
.sheet-actions { display: flex; flex-direction: column; gap: 9px; margin-top: 16px; }
.sheet-btn {
  display: flex; align-items: center; justify-content: center; gap: 8px;
  width: 100%; height: 44px; border-radius: 12px; font-size: 13px; font-weight: 600;
  background: rgba(255,255,255,.06); color: var(--text); border: 1px solid rgba(255,255,255,.08);
  transition: var(--transition);
}
.sheet-btn:active { transform: scale(.98); }
.sheet-btn.primary { background: linear-gradient(135deg, var(--gold), #d99a16); color: #1a1200; border: none; font-weight: 700; box-shadow: 0 4px 16px rgba(255,198,75,.25); }
.sheet-btn.gold { background: rgba(255,198,75,.15); color: var(--gold); border-color: rgba(255,198,75,.35); }

.playlist-mask { position:fixed;inset:0;z-index:120;display:grid;place-items:center;padding:18px;background:rgba(0,0,0,.62); }
.playlist-dialog { width:min(360px,calc(100vw - 28px));max-height:calc(100vh - 40px);overflow:hidden;border:1px solid rgba(255,255,255,.12);border-radius:14px;background:#17171b;color:#f7f3eb;box-shadow:0 20px 55px rgba(0,0,0,.4); }
.playlist-dialog-head { display:flex;align-items:flex-start;justify-content:space-between;gap:12px;padding:16px;border-bottom:1px solid rgba(255,255,255,.1); }
.playlist-dialog-head strong,.playlist-dialog-head small { display:block; }.playlist-dialog-head strong { font-size:16px; }.playlist-dialog-head small { margin-top:5px;color:var(--dim);font-size:11px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:270px; }.playlist-dialog-head button { color:var(--dim);font-size:25px;line-height:1;padding:0 4px; }
.playlist-options { max-height:360px;overflow:auto; }.playlist-options>button { display:flex;align-items:center;justify-content:space-between;gap:12px;width:100%;padding:13px 16px;border-bottom:1px solid rgba(255,255,255,.08);text-align:left;color:inherit; }.playlist-options>button:hover:not(:disabled) { background:rgba(255,255,255,.06); }.playlist-options>button strong,.playlist-options>button small { display:block; }.playlist-options>button strong { font-size:13px; }.playlist-options>button small { margin-top:4px;color:var(--dim);font-size:10px; }.playlist-options>button em { flex:none;color:var(--gold);font-size:11px;font-style:normal;font-weight:700; }.playlist-dialog-empty { padding:34px 16px;text-align:center;color:var(--dim);font-size:12px; }
</style>
