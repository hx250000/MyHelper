import { test, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { api, request } from '../src/api/client.js'

const originalFetch = globalThis.fetch
afterEach(() => { globalThis.fetch = originalFetch })

test('API methods use current controller routes and decode the response envelope', async () => {
  const calls = []
  globalThis.fetch = async (url, options) => {
    calls.push({ url, ...options })
    return new Response(JSON.stringify({ code: 0, data: { ok: true } }), { status: 200 })
  }
  assert.deepEqual(await api.register('小林', 'password123'), { ok: true })
  await api.conversations('user-id')
  await api.createConversation('user-id')
  await api.history('user-id', 7)
  await api.send('user-id', 7, '你好')
  assert.deepEqual(calls.map((call) => call.url), [
    '/api/users/register', '/api/conversation/my?userId=user-id', '/api/conversation/new',
    '/api/messages/history?userId=user-id&conversationId=7', '/api/chat/send',
  ])
  assert.deepEqual(JSON.parse(calls[4].body), { userId: 'user-id', conversationId: 7, content: '你好' })
  assert.equal(calls[4].method, 'POST')
})

test('business and upstream failures are visible and never retried', async () => {
  let calls = 0
  globalThis.fetch = async () => {
    calls++
    return new Response(JSON.stringify({ code: 50200, message: '大模型调用失败', data: null }), { status: 502 })
  }
  await assert.rejects(api.send('user', 1, '你好'), /大模型调用失败/)
  assert.equal(calls, 1)
  globalThis.fetch = async () => new Response(JSON.stringify({ code: 40001, message: '请等待回复' }))
  await assert.rejects(request('/test'), /请等待回复/)
})

test('non-JSON proxy failures and network failures produce readable errors', async () => {
  globalThis.fetch = async () => new Response('Bad Gateway', { status: 502 })
  await assert.rejects(request('/test'), /后端/)
  globalThis.fetch = async () => { throw new TypeError('Failed to fetch') }
  await assert.rejects(request('/test'), /网络/)
})

test('requests time out with no automatic resubmission', async () => {
  globalThis.fetch = (url, { signal }) => new Promise((resolve, reject) => {
    signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
  })
  await assert.rejects(request('/test', { timeout: 5 }), /超时/)
})
