import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createApp, h, nextTick } from 'vue'
import { createPinia } from 'pinia'
import KtvLibraryView from './KtvLibraryView.vue'
import api from '../../api/client'

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
  useRoute: () => ({ query: {} })
}))

describe('KtvLibraryView 刮削状态筛选与展示', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('加载歌曲时支持传递 scrapeStatus 筛选参数，并渲染已刮削/未刮削状态标签', async () => {
    const mockSongs = [
      {
        id: 1,
        title: '晴天',
        artist: '周杰伦',
        mediaType: 'KTV_VIDEO',
        language: '国语',
        tags: ['流行'],
        filePath: '/music/晴天.mp4',
        importSource: 'COPIED',
        playCount: 10,
        scraped: true
      },
      {
        id: 2,
        title: '未知曲目',
        artist: '未知歌手',
        mediaType: 'AUDIO',
        language: '未知',
        tags: [],
        filePath: '/music/track2.mp3',
        importSource: 'UNKNOWN',
        playCount: 0,
        scraped: false
      }
    ]

    const adminSongsSpy = vi.spyOn(api, 'adminSongs').mockResolvedValue({
      content: mockSongs,
      total: 2,
      totalPages: 1
    })

    const container = document.createElement('div')
    const app = createApp({
      render: () => h(KtvLibraryView)
    })
    app.use(createPinia())
    app.component('router-link', {
      props: ['to'],
      template: '<a><slot /></a>'
    })
    app.mount(container)

    await nextTick()
    await nextTick()

    expect(adminSongsSpy).toHaveBeenCalledWith(expect.objectContaining({
      page: 0,
      size: 20
    }))

    const selectElements = container.querySelectorAll('.filter-panel select')
    expect(selectElements.length).toBe(3)

    const scrapeSelect = selectElements[2]
    const options = Array.from(scrapeSelect.querySelectorAll('option')).map(o => ({
      value: o.value,
      text: o.textContent.trim()
    }))

    expect(options).toEqual([
      { value: '', text: '全部状态' },
      { value: 'SCRAPED', text: '已刮削' },
      { value: 'UNSCRAPED', text: '未刮削' }
    ])

    const headers = Array.from(container.querySelectorAll('th')).map(th => th.textContent.trim())
    expect(headers).toContain('刮削状态')

    const statusBadges = Array.from(container.querySelectorAll('tr td:nth-child(5) .status')).map(b => b.textContent.trim())
    expect(statusBadges).toEqual(['已刮削', '未刮削'])

    app.unmount()
  })
})
