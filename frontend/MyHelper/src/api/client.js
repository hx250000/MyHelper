const API_BASE = (import.meta.env?.VITE_API_BASE_URL || '/api').replace(/\/$/, '')

export async function request(path, { method = 'GET', body, timeout = 90000 } = {}) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeout)
  try {
    const response = await fetch(`${API_BASE}${path}`, {
      method,
      headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
    })
    const payload = await response.json().catch(() => null)
    if (!response.ok || !payload || payload.code !== 0) {
      throw new Error(payload?.message || (response.status >= 500
        ? '暂时无法连接后端，请确认服务已启动。'
        : `请求失败（HTTP ${response.status}），请稍后重试。`))
    }
    return payload.data
  } catch (error) {
    if (error.name === 'AbortError') throw new Error('请求超时，请检查服务状态。')
    if (error instanceof TypeError) throw new Error('网络连接失败，请检查后端服务和网络。')
    throw error
  } finally {
    clearTimeout(timer)
  }
}

export const api = {
  register: (username, password) => request('/users/register', { method: 'POST', body: { username, password } }),
  conversations: (userId) => request(`/conversation/my?${new URLSearchParams({ userId })}`),
  createConversation: (userId) => request('/conversation/new', { method: 'POST', body: { userId } }),
  history: (userId, conversationId) => request(`/messages/history?${new URLSearchParams({ userId, conversationId })}`),
  send: (userId, conversationId, content) => request('/chat/send', { method: 'POST', body: { userId, conversationId, content } }),
}
