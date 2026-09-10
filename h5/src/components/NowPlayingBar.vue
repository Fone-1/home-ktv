<template>
  <!-- 有歌曲正在播放时 / When a song is currently playing -->
  <div v-if="player.nowPlaying" class="now" role="button" aria-label="正在播放栏，点击查看歌词" @click="$router.push({ name: 'lyric' })">
    <div class="cover" :style="coverStyle">
      <Music2 v-if="!coverUrl" :size="20" />
    </div>
    <div class="grow">
      <div class="title">
        {{ player.nowPlaying.song?.title }}
        <span class="artist">· {{ player.nowPlaying.song?.artist }}</span>
      </div>
      <div class="meta-row">
        <span v-if="player.nowPlaying.orderedByNick" class="order-by">{{ player.nowPlaying.orderedByNick }} 点播</span>
        <span v-else class="order-by">欢唱中</span>
      </div>
      <div class="bar"><i :style="{ width: progressPct + '%' }"></i></div>
    </div>
    <div class="status-wrap">
      <div v-if="player.state === 'playing'" class="soundwave-bar" aria-hidden="true">
        <span></span><span></span><span></span>
      </div>
      <span class="chip" :class="player.state">{{ stateText }}</span>
    </div>
  </div>
  <!-- 无人点歌时的空状态 / Empty state when no songs are queued -->
  <div v-else class="now empty" role="button" @click="$router.push({ name: 'search' })">
    <div class="empty-icon">🎤</div>
    <div class="grow hint">
      <strong>客厅欢唱局已就绪</strong>
      <small>暂无正在播放歌曲，点击开启第一首</small>
    </div>
    <span class="go-order">去选歌 ›</span>
  </div>
</template>

<script setup>
/**
 * 底部正在播放栏组件 —— 显示当前歌曲信息、播放进度及状态。
 * 具备跳动声波律动动效与点歌人身份展示。
 *
 * Bottom now-playing bar — shows current song info, playback progress, and status,
 * with rhythm soundwave animations and who ordered the track.
 */
import { computed } from 'vue'
import { usePlayerStore } from '../stores/player'
import { Music2 } from 'lucide-vue-next'

const player = usePlayerStore()
const coverUrl = computed(() => player.nowPlaying?.song?.coverUrl || '')
const coverStyle = computed(() => coverUrl.value ? { backgroundImage: `url(${coverUrl.value})` } : {})

/**
 * 当前播放进度百分比（0–100）。
 *
 * Current playback progress percentage (0–100).
 */
const progressPct = computed(() => {
  const dur = player.nowPlaying?.song?.durationMs || 0
  if (!dur) return 0
  return Math.min(100, Math.round((player.positionMs / dur) * 100))
})

/**
 * 播放状态对应的中文展示文案。
 *
 * Chinese display text for current playback state.
 */
const stateText = computed(() => ({
  playing: '演唱中', paused: '已暂停', idle: ''
}[player.state] || ''))
</script>

<style scoped>
.now {
  min-height: 68px; background: linear-gradient(135deg, rgba(23,27,34,.95), rgba(30,36,46,.95));
  border: 1px solid var(--glass-border); border-left: 3px solid var(--coral);
  border-radius: var(--radius); padding: 10px 12px; display: flex; gap: 11px; align-items: center;
  box-shadow: 0 8px 24px rgba(0,0,0,.3); cursor: pointer; transition: transform .15s ease;
}
.now:active { transform: scale(.99); }
.now.empty {
  border-left: 3px solid var(--gold); background: rgba(24,29,37,.7);
  cursor: pointer;
}
.empty-icon { font-size: 22px; margin-right: 2px; }
.hint strong { display: block; font-size: 13px; color: var(--text); }
.hint small { display: block; font-size: 11px; color: var(--dim2); margin-top: 2px; }
.go-order { font-size: 11px; color: var(--gold); font-weight: 700; flex: none; }
.cover {
  width: 46px; height: 46px; border-radius: 8px; flex: none; display: grid; place-items: center;
  background: #202630 center / cover no-repeat; color: var(--dim2); border: 1px solid var(--glass-border);
}
.title { font-size: 13px; font-weight: 700; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.artist { color: var(--dim); font-weight: 400; font-size: 12px; }
.meta-row { margin-top: 3px; font-size: 10px; color: var(--dim2); display: flex; align-items: center; gap: 6px; }
.order-by { color: var(--gold); }
.bar { height: 4px; background: rgba(255,255,255,.06); border-radius: 3px; margin-top: 6px; overflow: hidden; }
.bar i { display:block;height:100%;background:var(--coral);transition:width .5s linear; }
.status-wrap { display: flex; flex-direction: column; align-items: center; gap: 4px; flex: none; }
.chip {
  color: #192019; border: 0; border-radius: 4px; padding: 3px 6px; font-size: 9px; font-weight: 800; background: var(--mint);
}
.chip.paused { background: rgba(255,255,255,.2); color: var(--text); }
.soundwave-bar { display: flex; align-items: flex-end; gap: 2px; height: 12px; }
.soundwave-bar span {
  width: 2.5px; height: 100%; background: var(--mint); border-radius: 1px;
  animation: soundwave 0.8s ease-in-out infinite alternate;
}
.soundwave-bar span:nth-child(2) { animation-delay: 0.25s; }
.soundwave-bar span:nth-child(3) { animation-delay: 0.5s; }
@keyframes soundwave {
  0% { height: 25%; }
  100% { height: 100%; }
}
</style>
