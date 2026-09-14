<template>
  <AdminLayout active="songs">
    <!-- 工具条：筛选 + 批量操作 / Toolbar: filter + batch actions -->
    <div class="toolbar">
      <span v-for="f in filters" :key="f.value" class="chip" :class="{ on: type === f.value }"
            @click="setType(f.value)">{{ f.label }}</span>
      <span class="grow"></span>
      <button v-if="selected.size" class="btn ghost" @click="openBatchConvert">批量转双轨（{{ selected.size }}）</button>
      <button v-if="selected.size" class="btn ghost" @click="openReparse">批量重解析（{{ selected.size }}）</button>
      <button class="btn" @click="scan" :disabled="scanning">⟳ 扫描</button>
    </div>

    <!-- 导入记录面板 / Import records panel -->
    <div class="imports card">
      <div class="imports-head">
        <strong>导入记录</strong>
        <div class="import-filters">
          <span v-for="f in importFilters" :key="f.value" class="chip" :class="{ on: importAction === f.value }"
                @click="setImportAction(f.value)">{{ f.label }}</span>
        </div>
      </div>
      <table class="tbl imports-table">
        <thead>
          <tr><th>源文件</th><th>处理结果</th><th>格式</th><th>MD5</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="item in imports" :key="item.id">
            <td class="source-cell">
              <div class="title">{{ item.sourceFilename }}</div>
              <div class="sub">{{ item.sourcePath }}</div>
              <div v-if="item.outputPath" class="sub">输出：{{ item.outputPath }}</div>
            </td>
            <td>
              <div><span class="tag" :class="importTagClass(item.action)">{{ importTagText(item.action) }}</span></div>
              <div class="sub">{{ item.reason || '—' }}</div>
            </td>
            <td>
              <div>{{ item.videoCodec || '—' }} / {{ item.audioCodec || '—' }}</div>
              <div class="sub">{{ item.outputFormat || '—' }} · {{ item.transcodeRequired ? '需转码' : '可直拷' }}</div>
            </td>
            <td class="md5-cell">
              <div class="sub">源：{{ shortMd5(item.sourceMd5) }}</div>
              <div class="sub">输出：{{ shortMd5(item.outputMd5) }}</div>
            </td>
            <td>
              <span class="link dim" :class="{ disabled: item.sourceDeleted }" @click="deleteSource(item)">
                {{ item.sourceDeleted ? '源已删除' : '删除源视频' }}
              </span>
            </td>
          </tr>
          <tr v-if="!imports.length"><td colspan="5" class="empty">暂无导入记录</td></tr>
        </tbody>
      </table>
    </div>

    <!-- 歌曲列表表格 / Song list table -->
    <table class="tbl">
      <thead>
        <tr><th><input type="checkbox" :checked="allSelected" @change="toggleAll" /></th><th>歌名</th><th>歌手</th><th>类型</th><th>时长</th><th>点唱</th><th>操作</th></tr>
      </thead>
      <tbody>
        <tr v-for="s in songs" :key="s.id" :class="{ unrec: isUnrec(s) }">
          <td><input type="checkbox" :checked="selected.has(s.id)" @change="toggle(s.id)" /></td>
          <td class="title">{{ s.title }}</td>
          <td>{{ s.artist }}</td>
          <td><span class="tag" :class="tagClass(s.mediaType)">{{ tagText(s.mediaType) }}</span></td>
          <td>{{ fmtDur(s.durationMs) }}</td>
          <td>{{ s.playCount }}</td>
          <td>
            <span class="link" @click="edit(s)">编辑</span> ·
            <span v-if="s.mediaType === 'MV' || !s.hasVocalTrack" class="link dual" :class="{ disabled: convertingSongs.has(s.id) }" @click="openConvert(s)">{{ convertingSongs.has(s.id) ? '转换中…' : '转双轨' }}</span>
            <span v-if="s.mediaType === 'MV' || !s.hasVocalTrack"> · </span>
            <span class="link transcode" :class="{ disabled: transcoding.has(s.id) }" @click="transcode(s)">{{ transcoding.has(s.id) ? '转码中…' : '转码' }}</span> ·
            <span class="link dim" @click="del(s)">删除</span>
          </td>
        </tr>
        <tr v-if="!songs.length"><td colspan="7" class="empty">暂无数据，点「扫描」入库</td></tr>
      </tbody>
    </table>

    <!-- 分页控件 / Pagination -->
    <div class="pager" v-if="totalPages > 1">
      <span class="link" :class="{ disabled: page === 0 }" @click="go(page - 1)">‹ 上一页</span>
      <span class="cur">{{ page + 1 }} / {{ totalPages }}</span>
      <span class="link" :class="{ disabled: page >= totalPages - 1 }" @click="go(page + 1)">下一页 ›</span>
    </div>

    <!-- 编辑曲目弹层 / Edit song modal -->
    <div v-if="editing" class="mask" @click.self="editing = null">
      <div class="modal">
        <div class="mt">编辑曲目</div>
        <label>歌名<input v-model="form.title" /></label>
        <label>歌手<input v-model="form.artist" /></label>
        <label>语种<input v-model="form.language" placeholder="国语/粤语/英语…" /></label>
        <label>歌手类型<select v-model="form.artistGender"><option value="未知">未知</option><option value="男歌手">男歌手</option><option value="女歌手">女歌手</option><option value="组合">组合</option></select></label>
        <label>歌词（粘贴 LRC，可选）<textarea v-model="form.lyricText" rows="4"></textarea></label>
        <div class="mr">
          <button class="btn ghost" @click="editing = null">取消</button>
          <button class="btn" @click="save">保存</button>
        </div>
      </div>
    </div>

    <!-- 批量重解析文件名弹层 / Batch re-parse filenames modal -->
    <div v-if="reparseOpen" class="mask" @click.self="reparseOpen = false">
      <div class="modal reparse-modal">
        <div class="mt">批量重解析文件名</div>
        <div class="rule-row"><label><input type="radio" v-model="reparseRule" value="artist_title" /> 歌手 - 歌名</label><label><input type="radio" v-model="reparseRule" value="title_artist" /> 歌名 - 歌手</label><button class="btn ghost" @click="previewReparse" :disabled="previewing">{{ previewing ? '解析中…' : '刷新预览' }}</button></div>
        <div class="preview-list">
          <div v-for="item in reparsePreview" :key="item.songId" class="preview-item" :class="{ bad: !item.recognized }">
            <div class="filename">{{ item.filename || item.error }}</div>
            <div class="change"><span>{{ item.currentArtist }} / {{ item.currentTitle }}</span><b>→</b><span>{{ item.recognized ? `${item.proposedArtist} / ${item.proposedTitle}` : '跳过' }}</span></div>
          </div>
        </div>
        <div class="mr"><span class="summary">可更新 {{ recognizedCount }} 首，跳过 {{ reparsePreview.length - recognizedCount }} 首</span><button class="btn ghost" @click="reparseOpen = false">取消</button><button class="btn" @click="applyReparse" :disabled="!recognizedCount || applying">{{ applying ? '应用中…' : '确认应用' }}</button></div>
      </div>
    </div>

    <!-- 单曲转双轨伴奏弹层 / Single song dual track modal -->
    <div v-if="convertOpen" class="mask" @click.self="convertOpen = false">
      <div class="modal">
        <div class="mt">单曲转双轨伴奏</div>
        <p class="sub" style="margin-bottom: 14px;">将《{{ convertingSong?.title }}》重构为支持原唱/伴唱切换的标准 KTV 视频。</p>
        <label>
          伴奏分离模式
          <select v-model="convertForm.mode">
            <option value="AI">UVR-MDX-Net 深度学习 AI (方案B: 母带级纯净伴奏, 默认)</option>
            <option value="DSP">极速声学消音 (DSP, 约2~5秒秒级转换)</option>
          </select>
        </label>
        <label>
          输出视频格式
          <select v-model="convertForm.outputFormat">
            <option value="mp4">MP4 (全设备最佳兼容，强烈推荐)</option>
            <option value="mkv">MKV (原生多音轨格式)</option>
          </select>
        </label>
        <label style="display:flex; align-items:center; gap:8px; cursor:pointer;">
          <input type="checkbox" v-model="convertForm.backupOriginal" style="width:auto; margin:0;" />
          保留原始单轨文件备份 (.original.bak)
        </label>
        <div class="mr">
          <button class="btn ghost" @click="convertOpen = false" :disabled="converting">取消</button>
          <button class="btn" @click="applyConvert" :disabled="converting">{{ converting ? '提交中…' : '开始转换' }}</button>
        </div>
      </div>
    </div>

    <!-- 批量转双轨伴奏弹层 / Batch convert dual track modal -->
    <div v-if="batchOpen" class="mask" @click.self="batchOpen = false">
      <div class="modal">
        <div class="mt">批量转双轨伴奏</div>
        <p class="sub" style="margin-bottom: 14px;">已选择 {{ selected.size }} 首歌曲进行后台批量消音与双轨重构。</p>
        <label>
          分离算法
          <select v-model="batchForm.mode">
            <option value="AI">UVR-MDX-Net 深度学习 AI (方案B: 默认推荐)</option>
            <option value="DSP">极速声学消音 (DSP 毫秒级重构)</option>
          </select>
        </label>
        <label>
          并发任务数 (防止 NAS 过载)
          <select v-model.number="batchForm.concurrency">
            <option :value="1">1 个任务并发 (适合低算力设备)</option>
            <option :value="2">2 个任务并发 (默认平衡)</option>
            <option :value="3">3 个任务并发 (高性能主机)</option>
          </select>
        </label>
        <div v-if="batchProgress && batchProgress.running" class="batch-progress-box" style="margin: 12px 0;">
          <div style="display:flex; justify-content:space-between; font-size:12px; margin-bottom:4px;">
            <span>{{ batchProgress.lastMessage || '正在处理中...' }}</span>
            <span>{{ batchProgress.progress }}%</span>
          </div>
          <div style="background:rgba(255,255,255,.1); height:6px; border-radius:3px; overflow:hidden;">
            <div :style="{ width: batchProgress.progress + '%', background:'var(--gold)', height:'100%', transition:'width 0.3s' }"></div>
          </div>
        </div>
        <div class="mr">
          <button class="btn ghost" @click="batchOpen = false">关闭</button>
          <button class="btn" @click="applyBatchConvert" :disabled="batchSubmitting">{{ batchSubmitting ? '已提交后台' : '开始批量转换' }}</button>
        </div>
      </div>
    </div>
  </AdminLayout>
</template>

<script setup>
/**
 * 歌曲管理页面 —— 浏览、筛选、编辑、转码、删除曲目，以及导入记录查看和批量重解析文件名。
 *
 * Song management page — browse, filter, edit, transcode, delete tracks,
 * view import records, and batch re-parse filenames.
 */
import { ref, onMounted, reactive, computed } from 'vue'
import api from '../../api/client'
import AdminLayout from './AdminLayout.vue'
import { alertDialog, confirmDialog } from '../../composables/useDialog'

/** 歌曲类型筛选选项 / Song type filter options */
const filters = [
  { value: '', label: '全部' }, { value: 'KTV_VIDEO', label: 'KTV版' },
  { value: 'MV', label: 'MV版' }, { value: 'AUDIO', label: '音频版' },
  { value: 'unrecognized', label: '未识别' }
]
/** 导入记录筛选选项 / Import record filter options */
const importFilters = [
  { value: '', label: '全部记录' },
  { value: 'COPIED', label: '已复制' },
  { value: 'TRANSCODED', label: '已转码' },
  { value: 'SKIPPED_SOURCE_MD5_DUPLICATE', label: '源MD5重复' },
  { value: 'SKIPPED_OUTPUT_MD5_DUPLICATE', label: '输出MD5重复' },
  { value: 'FAILED', label: '失败' }
]

// —— 响应式状态 / Reactive state ——
const songs = ref([])
const imports = ref([])
const type = ref('')
const importAction = ref('')
const page = ref(0)
const totalPages = ref(1)
const scanning = ref(false)
const editing = ref(null)
const transcoding = ref(new Set())
const form = reactive({ title: '', artist: '', language: '', artistGender: '未知', lyricText: '' })
const selected = ref(new Set())
const reparseOpen = ref(false)
const reparseRule = ref('artist_title')
const reparsePreview = ref([])
const previewing = ref(false)
const applying = ref(false)

// 双轨伴奏转换状态
const convertingSongs = ref(new Set())
const convertOpen = ref(false)
const convertingSong = ref(null)
const converting = ref(false)
const convertForm = reactive({ mode: 'AI', backupOriginal: false, outputFormat: 'mp4' })
const batchOpen = ref(false)
const batchSubmitting = ref(false)
const batchForm = reactive({ mode: 'AI', concurrency: 1 })
const batchProgress = ref(null)
let batchTimer = null
/** 是否全选当前页 / Whether all songs on current page are selected */
const allSelected = computed(() => songs.value.length > 0 && songs.value.every(song => selected.value.has(song.id)))
/** 批量重解析中可识别的曲目数 / Number of recognizable tracks in batch re-parse preview */
const recognizedCount = computed(() => reparsePreview.value.filter(item => item.recognized).length)

/**
 * 加载当前筛选条件下的歌曲分页列表。
 *
 * Load paginated song list under current filter.
 */
async function load() {
  const r = await api.adminSongs(type.value, page.value, 20).catch(() => ({ content: [], totalPages: 1 }))
  songs.value = r.content || []
  selected.value = new Set([...selected.value].filter(id => songs.value.some(song => song.id === id)))
  totalPages.value = r.totalPages || 1
}
/**
 * 加载导入记录列表。
 *
 * Load import record list.
 */
async function loadImports() {
  const r = await api.adminImports(importAction.value, 0, 20).catch(() => ({ content: [] }))
  imports.value = r.content || []
}
onMounted(async () => { await load(); await loadImports() })

function setType(t) { type.value = t; page.value = 0; load() }
function setImportAction(v) { importAction.value = v; loadImports() }
function go(p) { if (p >= 0 && p < totalPages.value) { page.value = p; load() } }
function toggle(id) { const next = new Set(selected.value); next.has(id) ? next.delete(id) : next.add(id); selected.value = next }
function toggleAll() { selected.value = allSelected.value ? new Set() : new Set(songs.value.map(song => song.id)) }

/**
 * 触发服务端扫描曲库目录，完成后刷新歌曲列表和导入记录。
 *
 * Trigger server-side media library scan, then refresh song list and import records.
 */
async function scan() {
  scanning.value = true
  try { await api.adminScan(); await Promise.all([load(), loadImports()]) } finally { scanning.value = false }
}

/**
 * 打开编辑弹层，将选中歌曲的信息填入表单。
 * @param {Object} s - 歌曲对象 / Song object
 */
function edit(s) {
  editing.value = s
  form.title = s.title; form.artist = s.artist; form.language = ''; form.artistGender = s.artistGender || '未知'; form.lyricText = ''
}
/**
 * 提交编辑表单，保存歌曲信息到服务端。
 *
 * Submit edit form and persist song info to server.
 */
async function save() {
  try {
    await api.adminEditSong(editing.value.id, {
      title: form.title, artist: form.artist,
      language: form.language || null, artistGender: form.artistGender, lyricText: form.lyricText || null
    })
    editing.value = null
    await load()
  } catch (e) { await alertDialog(e.message || '保存失败') }
}
/**
 * 删除指定歌曲记录（确认后）。
 * @param {Object} s - 歌曲对象 / Song object
 */
async function del(s) {
  if (!await confirmDialog(`将删除《${s.title}》的记录。`, { title: '删除歌曲记录', tone: 'warning' })) return
  try { await api.adminDeleteSong(s.id); await load() } catch (e) { await alertDialog(e.message || '删除失败') }
}
/**
 * 触发歌曲转码，生成 Android TV 兼容副本。
 * @param {Object} s - 歌曲对象 / Song object
 */
async function transcode(s) {
  if (transcoding.value.has(s.id)) return
  if (!await confirmDialog(`将为《${s.title}》生成 Android TV 兼容副本，原文件会保留。`, { title: '转码歌曲' })) return
  transcoding.value = new Set([...transcoding.value, s.id])
  try {
    const result = await api.adminTranscodeSong(s.id)
    const mb = ((result.outputBytes || 0) / 1024 / 1024).toFixed(1)
    await alertDialog(`转码完成（${mb} MB）\n${result.outputPath}`, { title: '转码完成', tone: 'success' })
    await load()
  } catch (e) {
    await alertDialog(e.message || '转码失败，请确认服务端已安装 FFmpeg 且曲库目录可写')
  } finally {
    const next = new Set(transcoding.value); next.delete(s.id); transcoding.value = next
  }
}
/**
 * 删除导入记录对应的源视频文件。
 * @param {Object} item - 导入记录对象 / Import record object
 */
async function deleteSource(item) {
  if (item.sourceDeleted) return
  if (!await confirmDialog(item.sourceFilename, { title: '删除源视频', tone: 'warning' })) return
  try {
    await api.adminDeleteImportSource(item.id)
    await loadImports()
  } catch (e) { await alertDialog(e.message || '删除源视频失败') }
}
/**
 * 打开批量重解析弹层并自动加载预览。
 *
 * Open batch re-parse modal and auto-load preview.
 */
async function openReparse() { reparseOpen.value = true; await previewReparse() }
/**
 * 根据选中歌曲和解析规则生成文件名重解析预览。
 *
 * Generate filename re-parse preview based on selected songs and parse rule.
 */
async function previewReparse() {
  previewing.value = true
  try { reparsePreview.value = await api.adminPreviewReparse([...selected.value], reparseRule.value) }
  catch (error) { await alertDialog(error.message || '预览失败') }
  finally { previewing.value = false }
}
/**
 * 确认并执行批量重解析，将识别结果写入歌曲记录。
 *
 * Confirm and apply batch re-parse, writing recognized results to song records.
 */
async function applyReparse() {
  if (!await confirmDialog(`将按当前规则更新 ${recognizedCount.value} 首歌曲。`, { title: '确认批量重解析' })) return
  applying.value = true
  try {
    const result = await api.adminApplyReparse([...selected.value], reparseRule.value)
    await alertDialog(`更新 ${result.updated} 首，跳过 ${result.skipped} 首。`, { title: '重解析完成', tone: 'success' })
    reparseOpen.value = false; selected.value = new Set(); await load()
  } catch (error) { await alertDialog(error.message || '重解析失败') }
  finally { applying.value = false }
}

/** 打开单曲转双轨伴奏弹层 */
function openConvert(s) {
  convertingSong.value = s
  convertOpen.value = true
}

/** 提交单曲转双轨伴奏 */
async function applyConvert() {
  if (!convertingSong.value) return
  const sId = convertingSong.value.id
  converting.value = true
  try {
    await api.convertSongDualTrack(sId, { ...convertForm })
    convertingSongs.value.add(sId)
    convertOpen.value = false
    await alertDialog('双轨转换任务已提交后台处理，转换完成后将自动点亮伴唱轨！', { title: '提交成功', tone: 'success' })
    // 10秒后自动刷新曲目状态
    setTimeout(async () => {
      convertingSongs.value.delete(sId)
      await load()
    }, 6000)
  } catch (error) {
    await alertDialog(error.message || '提交转换任务失败')
  } finally {
    converting.value = false
  }
}

/** 打开批量转双轨弹层 */
function openBatchConvert() {
  batchOpen.value = true
  pollBatchProgress()
}

/** 提交批量转换任务 */
async function applyBatchConvert() {
  if (!selected.value.size) return
  batchSubmitting.value = true
  try {
    await api.batchConvertSongDualTrack({
      songIds: [...selected.value],
      mode: batchForm.mode,
      concurrency: batchForm.concurrency
    })
    pollBatchProgress()
    await alertDialog(`已为 ${selected.value.size} 首歌曲提交批量转双轨队列！`, { title: '批量任务已提交', tone: 'success' })
  } catch (error) {
    await alertDialog(error.message || '批量转换提交失败')
  } finally {
    batchSubmitting.value = false
  }
}

/** 轮询批量转换进度 */
async function pollBatchProgress() {
  if (batchTimer) clearInterval(batchTimer)
  const query = async () => {
    try {
      const res = await api.getSongConvertProgress()
      batchProgress.value = res.data || res
      if (!batchProgress.value?.running && batchTimer) {
        clearInterval(batchTimer)
        batchTimer = null
        await load()
      }
    } catch (ignored) {}
  }
  await query()
  batchTimer = setInterval(query, 2000)
}

// —— 工具函数 / Utility functions ——
/** 判断歌曲是否未被识别（歌手为"未知歌手"） / Check if song is unrecognized (artist = "未知歌手") */
function isUnrec(s) { return s.artist === '未知歌手' }
/** 歌曲媒体类型中文标签映射 / Chinese label mapping for song media type */
function tagText(t) { return { KTV_VIDEO: 'KTV', MV: 'MV', AUDIO: '音频' }[t] || '' }
/** 歌曲媒体类型 CSS 类名映射 / CSS class mapping for song media type */
function tagClass(t) { return { KTV_VIDEO: 'tag-ktv', MV: 'tag-mv', AUDIO: 'tag-audio' }[t] || 'tag-audio' }
/** 格式化毫秒时长为 m:ss 格式 / Format millisecond duration to m:ss */
function fmtDur(ms) { if (!ms) return '—'; const s = Math.round(ms / 1000); return `${Math.floor(s/60)}:${String(s%60).padStart(2,'0')}` }
/** 导入操作中文标签映射 / Chinese label mapping for import action */
function importTagText(action) { return { COPIED: '已复制', TRANSCODED: '已转码', SKIPPED_SOURCE_MD5_DUPLICATE: '源MD5重复', SKIPPED_OUTPUT_MD5_DUPLICATE: '输出MD5重复', FAILED: '失败' }[action] || action }
/** 导入操作 CSS 类名映射 / CSS class mapping for import action */
function importTagClass(action) { return { COPIED: 'tag-ktv', TRANSCODED: 'tag-mv', SKIPPED_SOURCE_MD5_DUPLICATE: 'tag-audio', SKIPPED_OUTPUT_MD5_DUPLICATE: 'tag-audio', FAILED: 'tag-failed' }[action] || 'tag-audio' }
/** 截短 MD5 显示（首尾各8位） / Truncate MD5 for display (first & last 8 chars) */
function shortMd5(v) { return v ? `${v.slice(0, 8)}...${v.slice(-8)}` : '—' }
</script>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 16px; flex-wrap: wrap; }
.imports { margin-bottom: 18px; background: var(--panel2); border: 1px solid var(--glass-border); border-radius: var(--radius); padding: 14px; }
.imports-head { display:flex; align-items:center; justify-content:space-between; gap:12px; margin-bottom: 10px; }
.import-filters { display:flex; gap:8px; flex-wrap:wrap; }
.chip { background: var(--panel2); border: 1px solid var(--glass-border); border-radius: 999px; padding: 6px 14px; font-size: 12px; color: var(--dim); cursor: pointer; }
.chip.on { color: var(--gold); border-color: rgba(240,199,66,.25); background: var(--gold-glow); }
.tbl { width: 100%; border-collapse: collapse; font-size: 13px; }
.tbl th { text-align: left; color: var(--dim2); font-weight: 600; padding: 10px; border-bottom: 1px solid var(--line); font-size: 12px; }
.tbl td { padding: 10px; border-bottom: 1px solid var(--line); }
.tbl tr:hover td { background: rgba(255,255,255,.02); }
.tbl tr.unrec td { background: rgba(248,113,113,.03); }
.title { font-weight: 600; }
.sub { color: var(--dim2); font-size: 11px; line-height: 1.45; word-break: break-all; }
.source-cell, .md5-cell { min-width: 0; }
.link { color: var(--gold); cursor: pointer; }
.link.dim { color: var(--dim2); }
.link.transcode { color: var(--green); }
.link.dual { color: var(--gold); font-weight: 600; }
.link.disabled { opacity: .4; pointer-events: none; }
.tag-failed { background: rgba(239,68,68,.12); color: #ffb4b4; }
.empty { text-align: center; color: var(--dim2); padding: 30px; }
.pager { display: flex; justify-content: center; gap: 14px; margin-top: 16px; font-size: 12px; color: var(--dim); }
.mask { position: fixed; inset: 0; background: rgba(5,6,10,.7); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { background: rgba(20,24,34,.98); border: 1px solid var(--glass-border); border-radius: 16px; padding: 24px; width: 360px; }
.mt { font-size: 15px; font-weight: 700; margin-bottom: 16px; }
.modal label { display: block; font-size: 12px; color: var(--dim); margin-bottom: 12px; }
.modal input, .modal textarea, .modal select { width: 100%; margin-top: 6px; background: var(--panel2); border: 1px solid var(--glass-border);
  border-radius: 8px; padding: 8px 10px; color: var(--text); font-size: 13px; }
.mr { display: flex; gap: 10px; justify-content: flex-end; margin-top: 8px; }
.reparse-modal { width: min(760px, 90vw); max-height: 82vh; display: flex; flex-direction: column; }.rule-row { display:flex;align-items:center;gap:18px;margin-bottom:12px }.rule-row label { margin:0;display:flex;align-items:center;gap:5px }.rule-row .btn { margin-left:auto }.preview-list { overflow:auto;border:1px solid var(--line);border-radius:10px;padding:0 12px;min-height:100px }.preview-item { padding:10px 0;border-bottom:1px solid var(--line) }.preview-item:last-child { border-bottom:0 }.preview-item.bad { opacity:.55 }.filename { color:var(--dim2);font-size:11px;margin-bottom:5px }.change { display:grid;grid-template-columns:1fr 25px 1fr;gap:7px;align-items:center;font-size:12px }.change b { color:var(--gold);text-align:center }.summary { margin-right:auto;color:var(--dim);font-size:12px;align-self:center }
</style>
