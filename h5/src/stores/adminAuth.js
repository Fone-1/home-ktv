import { defineStore } from 'pinia'
import api from '../api/client'

/**
 * 管理员安全鉴权状态 Store：
 * 维护 PIN 码启用状态、是否已解锁、短期 Token 及解锁弹窗交互。
 */
export const useAdminAuthStore = defineStore('adminAuth', {
  state: () => ({
    token: localStorage.getItem('ktv_admin_token') || null,
    pinEnabled: false,
    pinSet: false,
    readRequireAuth: false,
    unlocked: true,
    showUnlockModal: false,
    pendingAction: null
  }),
  actions: {
    /**
     * 查询后台安全鉴权状态
     */
    async fetchStatus() {
      try {
        const res = await api.adminAuthStatus()
        this.pinEnabled = Boolean(res?.pin_enabled)
        this.pinSet = Boolean(res?.pin_set)
        this.readRequireAuth = Boolean(res?.read_require_auth)
        this.unlocked = Boolean(res?.unlocked)
      } catch {
        // 请求失败时不阻断基本渲染
      }
    },

    /**
     * 提交 PIN 码解锁
     * @param {string} pin
     */
    async unlock(pin) {
      const res = await api.adminAuthLogin(pin)
      if (res?.token) {
        this.token = res.token
        this.unlocked = true
        this.showUnlockModal = false
        try {
          localStorage.setItem('ktv_admin_token', res.token)
        } catch {}
        if (typeof this.pendingAction === 'function') {
          const fn = this.pendingAction
          this.pendingAction = null
          fn()
        }
        return true
      }
      return false
    },

    /**
     * 主动注销并重新锁定
     */
    async lock() {
      try {
        await api.adminAuthLogout()
      } catch {}
      this.token = null
      this.unlocked = false
      try {
        localStorage.removeItem('ktv_admin_token')
      } catch {}
      await this.fetchStatus()
    },

    /**
     * 触发解锁弹窗，并在成功后执行回调
     * @param {Function} [onSuccess]
     */
    requireUnlock(onSuccess = null) {
      this.pendingAction = onSuccess
      this.showUnlockModal = true
    }
  }
})
