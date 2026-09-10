<template>
  <div class="page">
    <header class="topbar">
      <div class="brand">
        <img class="brand-mark" src="../assets/home-ktv-logo.png" alt="Home KTV" />
        <div><b>Home KTV</b><small>客厅欢唱局</small></div>
      </div>
      <div class="header-badges">
        <router-link v-if="player.queueCount > 0" :to="{ name: 'queue' }" class="wait-pill">
          <ListMusic :size="12" /><span>待唱 {{ player.queueCount }} 首</span><i>›</i>
        </router-link>
        <span class="room" :class="{ offline: !player.tvOnline }">
          <i></i>{{ player.tvOnline ? '电视在线' : '电视离线' }}
        </span>
      </div>
    </header>

    <section class="sec greeting">
      <h1>{{ timeGreeting }}，{{ user.nickname }}</h1>
      <p>想唱什么？曲库已经准备好了。</p>
    </section>

    <!-- 搜索条入口 / Search Entry Bar -->
    <section class="sec">
      <div class="search" @click="$router.push({ name: 'search' })">
        <Search :size="19" /><span class="ph">搜索歌名、歌手或拼音首字母</span>
        <span class="search-btn-mock">搜索</span>
      </div>
    </section>

    <!-- 当前播放条 / Now Playing Bar -->
    <section class="sec"><NowPlayingBar /></section>

    <!-- 分类宫格（4x2 对称 8 宫格金刚区）/ Category Grid (4x2 Symmetric 8-Grid) -->
    <section class="sec">
      <div class="quick-grid">
        <div v-for="c in cats" :key="c.label" class="cat" @click="onCat(c)">
          <div class="ic"><component :is="c.icon" :size="18" /></div>{{ c.label }}
        </div>
      </div>
    </section>

    <!-- 首页快捷选歌专区（热门/新歌/对唱/KTV精选）/ Home Quick Song Tabs -->
    <section class="sec grow">
      <div class="song-tabs-bar">
        <button v-for="t in songTabs" :key="t.key" class="stab-btn"
                :class="{ on: activeTab === t.key }"
                @click="switchTab(t.key)">
          {{ t.label }}
        </button>
      </div>

      <div v-if="tabLoading" class="tip">加载推荐歌曲中…</div>
      <div v-else-if="!currentSongs.length" class="tip">曲库暂无相关歌曲，先去后台扫描入库</div>
      <template v-else>
        <SongRow v-for="(s, i) in currentSongs" :key="s.id" :song="s"
                 :rank="activeTab === 'hot' ? i + 1 : 0"
                 :ordered="orderedIds.has(s.id)" @order="handleOrder" />
        <div class="more-bar">
          <router-link :to="{ name: 'browse' }" class="more-link">
            浏览曲库全部分类与歌手 <ChevronRight :size="15" />
          </router-link>
        </div>
      </template>
    </section>

    <TabBar active="home" />
  </div>
</template>

<script setup>
/**
 * 首页视图 —— 家庭KTV 主页面。
 * 包含：电视在线状态、时段温情问候、搜索入口、当前播放条、对称 8 宫格金刚区、多模式快捷点歌专区。
 *
 * Home view — the main page of Home KTV.
 * Contains: TV online status, dynamic greeting, search entry, now-playing bar,
 * symmetric 8-category grid, and multi-tab quick song ordering area.
 */
import { ref, onMounted, reactive, computed } from 'vue'
import { useRouter } from 'vue-router'
import api, { makeControls } from '../api/client'
import { useUserStore } from '../stores/user'
import { usePlayerStore } from '../stores/player'
import { useToast } from '../composables/useToast'
import TabBar from '../components/TabBar.vue'
import SongRow from '../components/SongRow.vue'
import NowPlayingBar from '../components/NowPlayingBar.vue'
import {
  Search, UserRound, Sparkles, UsersRound, ListMusic, Heart,
  Languages, LayoutGrid, History, ChevronRight
} from 'lucide-vue-next'

const router = useRouter()
const user = useUserStore()
const player = usePlayerStore()
const { toast } = useToast()
const controls = makeControls(user.clientToken)

/** 根据当前时间段生成温情问候 / Generate dynamic greeting based on time of day */
const timeGreeting = computed(() => {
  const h = new Date().getHours()
  if (h >= 5 && h < 11) return '早上好'
  if (h >= 11 && h < 13) return '中午好'
  if (h >= 13 && h < 18) return '下午好'
  return '晚上好'
})

/** 快捷选歌选项卡配置 / Quick song selection tabs */
const songTabs = [
  { key: 'hot', label: '今晚热门' },
  { key: 'new', label: '最新入库' },
  { key: 'duet', label: '甜蜜对唱' },
  { key: 'ktv', label: 'KTV精选' }
]
const activeTab = ref('hot')
const tabLoading = ref(false)
const songCache = reactive({
  hot: [],
  new: [],
  duet: [],
  ktv: []
})

const orderedIds = reactive(new Set())

/** 当前展示的歌曲列表 / Currently displayed songs */
const currentSongs = computed(() => songCache[activeTab.value] || [])

/** 首页分类宫格数据（4x2 对称 8 宫格金刚区） / 8-item symmetric category grid */
const cats = [
  { icon: UserRound, label: '歌手' },
  { icon: Languages, label: '语种' },
  { icon: LayoutGrid, label: '分类' },
  { icon: Sparkles, label: '新歌' },
  { icon: UsersRound, label: '对唱' },
  { icon: ListMusic, label: '歌单' },
  { icon: History, label: '最近唱' },
  { icon: Heart, label: '我的收藏' }
]

/**
 * 页面挂载时加载默认分类歌曲。
 * Load default tab songs on mount.
 */
onMounted(() => {
  loadTabSongs(activeTab.value)
})

/**
 * 切换快捷选歌 Tab 并加载数据
 * Switch quick tab and load data if not cached
 * @param {string} key
 */
async function switchTab(key) {
  activeTab.value = key
  if (!songCache[key]?.length) {
    await loadTabSongs(key)
  }
}

/**
 * 加载指定选项卡的歌曲列表
 * Load songs for the specified tab
 * @param {string} key
 */
async function loadTabSongs(key) {
  tabLoading.value = true
  try {
    if (key === 'hot') {
      let list = await api.ranking(30).catch(() => [])
      if (!list.length) list = await api.newSongs().catch(() => [])
      songCache.hot = list
    } else if (key === 'new') {
      songCache.new = await api.newSongs().catch(() => [])
    } else if (key === 'duet') {
      songCache.duet = await api.browseSongs({ vocalForm: '对唱', limit: 30 }).catch(() => [])
    } else if (key === 'ktv') {
      songCache.ktv = await api.browseSongs({ mediaType: 'KTV_VIDEO', limit: 30 }).catch(() => [])
    }
  } finally {
    tabLoading.value = false
  }
}

/**
 * 点歌：将指定歌曲加入播放队列。
 * @param {Object} song - 歌曲对象，需含 id 字段
 *
 * Order a song: add it to the playback queue.
 * @param {Object} song - Song object, must contain an `id` field
 */
function handleOrder(song) {
  orderedIds.add(song.id)
}

/**
 * 点歌：外部兼容点歌方法
 */
async function order(song) {
  try {
    await controls.order(song.id)
    orderedIds.add(song.id)
    toast(`已加入队列 · 待唱第 ${player.queueCount || 1} 首`)
  } catch (e) {
    if (e.code === 'SONG_IN_QUEUE') {
      toast(e.message || '这首歌已在队列中')
    } else {
      toast(e.message || '点歌失败')
    }
  }
}

/**
 * 根据分类条目跳转到对应页面。
 * @param {Object} c - 分类对象，含 label 字段
 *
 * Navigate to the corresponding page based on category item.
 * @param {Object} c - Category object with a `label` field
 */
function onCat(c) {
  if (c.label === '歌手') router.push({ name: 'browse', query: { tab: 'artists' } })
  else if (c.label === '语种') router.push({ name: 'browse', query: { tab: 'languages' } })
  else if (c.label === '分类') router.push({ name: 'browse', query: { tab: 'tags' } })
  else if (c.label === '歌单') router.push({ name: 'playlists' })
  else if (c.label === '新歌') router.push({ name: 'artist', params: { name: 'all' }, query: { mode: 'all', sort: 'new' } })
  else if (c.label === '对唱') router.push({ name: 'artist', params: { name: 'all' }, query: { mode: 'vocalForm', value: '对唱' } })
  else if (c.label === '最近唱') router.push({ name: 'recent-history' })
  else if (c.label === '我的收藏') router.push({ name: 'favorites' })
  else toast('分类「' + c.label + '」即将开放')
}
</script>

<style scoped>
.page { min-height: 100vh; padding-bottom: 74px; display: flex; flex-direction: column; }
.topbar { height: 58px; padding: 8px 16px 0; display: flex; align-items: center; justify-content: space-between; }
.brand { display:flex;align-items:center;gap:9px; }.brand-mark { width:30px;height:30px;border-radius:7px;object-fit:cover; }
.brand b,.brand small { display:block; }.brand b { font-size:14px; }.brand small { margin-top:2px;color:var(--dim2);font-size:9px; }
.header-badges { display: flex; align-items: center; gap: 8px; }
.wait-pill {
  display: flex; align-items: center; gap: 4px; padding: 4px 8px; border-radius: 999px;
  background: rgba(255,198,75,.12); border: 1px solid rgba(255,198,75,.3);
  color: var(--gold); font-size: 10px; font-weight: 600;
}
.wait-pill i { font-style: normal; margin-left: 2px; }
.room { display:flex;align-items:center;gap:6px;color:var(--mint);font-size:11px; }
.room i { width:6px;height:6px;border-radius:50%;background:var(--mint); }
.room.offline { color: var(--dim2); }
.room.offline i { background: var(--dim2); }

.sec { padding: 0 16px; margin-top: 12px; }
.greeting { margin-top:18px; }.greeting h1 { font-size:24px;line-height:1.2; }.greeting p { margin-top:5px;color:var(--dim);font-size:12px; }
.search {
  height:48px;display:flex;align-items:center;gap:9px;background:var(--panel);border:1px solid rgba(255,198,75,.32);border-radius:10px;
  padding:0 14px;color:var(--gold);font-size:13px;box-shadow:0 4px 20px rgba(0,0,0,.25);cursor:pointer;
  transition: border-color .2s;
}
.search:active { border-color: var(--gold); }
.search .ph { color: var(--dim2); flex: 1; }
.search-btn-mock {
  background: var(--gold); color: #1e1e2d; font-size: 11px; font-weight: 700;
  padding: 5px 10px; border-radius: 6px; flex: none;
}

.quick-grid { display:grid;grid-template-columns:repeat(4,1fr);gap:14px 4px;padding: 4px 0; }
.cat {
  color:var(--dim);padding:2px 0;text-align:center;font-size:11px;transition:var(--transition);
  cursor: pointer;
}
.cat:active { transform: scale(.95); }
.cat .ic {
  width:44px;height:44px;display:grid;place-items:center;margin:0 auto 6px;
  border:1px solid rgba(255,255,255,.08);border-radius:50%;color:var(--gold);
  background:linear-gradient(135deg, var(--panel2), #141922);
  box-shadow: 0 4px 14px rgba(0,0,0,.3);
}
.cat:active .ic { border-color: rgba(255,198,75,.4); }

.song-tabs-bar {
  display: flex; gap: 8px; border-bottom: 1px solid var(--line); padding-bottom: 10px; margin-bottom: 6px;
}
.stab-btn {
  padding: 6px 14px; border-radius: 999px; font-size: 12px; font-weight: 600; color: var(--dim2);
  background: var(--panel2); border: 1px solid var(--glass-border); transition: var(--transition);
}
.stab-btn.on {
  color: var(--gold); background: rgba(255,198,75,.14); border-color: rgba(255,198,75,.35);
}
.more-bar { padding: 18px 0 10px; text-align: center; }
.more-link {
  display: inline-flex; align-items: center; gap: 4px; font-size: 12px; color: var(--dim);
  padding: 8px 16px; border-radius: 999px; background: rgba(255,255,255,.04); border: 1px solid var(--line);
}
.more-link:active { color: var(--gold); border-color: var(--gold); }
.tip { color: var(--dim2); font-size: 13px; padding: 20px 0; text-align: center; }
</style>
