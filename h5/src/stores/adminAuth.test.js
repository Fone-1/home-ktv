import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAdminAuthStore } from './adminAuth'
import api from '../api/client'

describe('useAdminAuthStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.restoreAllMocks()
    localStorage.clear()
  })

  it('fetchStatus 同步 PIN 状态与解锁状态', async () => {
    vi.spyOn(api, 'adminAuthStatus').mockResolvedValue({
      pin_enabled: true,
      pin_set: true,
      read_require_auth: false,
      unlocked: false
    })

    const store = useAdminAuthStore()
    await store.fetchStatus()

    expect(store.pinEnabled).toBe(true)
    expect(store.pinSet).toBe(true)
    expect(store.unlocked).toBe(false)
  })

  it('unlock 成功保存 Token 并置位 unlocked', async () => {
    vi.spyOn(api, 'adminAuthLogin').mockResolvedValue({
      token: 'test-admin-token-1234',
      expires_in: 7200
    })

    const store = useAdminAuthStore()
    store.showUnlockModal = true

    const result = await store.unlock('123456')
    expect(result).toBe(true)
    expect(store.token).toBe('test-admin-token-1234')
    expect(store.unlocked).toBe(true)
    expect(store.showUnlockModal).toBe(false)
    expect(localStorage.getItem('ktv_admin_token')).toBe('test-admin-token-1234')
  })

  it('lock 注销并清空本地 Token', async () => {
    const logoutSpy = vi.spyOn(api, 'adminAuthLogout').mockResolvedValue({ success: true })
    vi.spyOn(api, 'adminAuthStatus').mockResolvedValue({
      pin_enabled: true,
      unlocked: false
    })

    const store = useAdminAuthStore()
    store.token = 'existing-token'
    store.unlocked = true
    localStorage.setItem('ktv_admin_token', 'existing-token')

    await store.lock()

    expect(logoutSpy).toHaveBeenCalled()
    expect(store.token).toBeNull()
    expect(store.unlocked).toBe(false)
    expect(localStorage.getItem('ktv_admin_token')).toBeNull()
  })
})
