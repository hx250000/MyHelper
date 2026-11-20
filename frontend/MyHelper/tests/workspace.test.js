import { test } from 'node:test'
import assert from 'node:assert/strict'
import { conversationTitle, demoReply, readWorkspace, saveWorkspace, validateRegistration } from '../src/lib/workspace.js'

test('registration follows backend username and UTF-8 password rules', () => {
  assert.equal(validateRegistration(' 小林 ', 'password123'), '')
  assert.match(validateRegistration(' ', 'password123'), /用户名/)
  assert.match(validateRegistration('a'.repeat(65), 'password123'), /64/)
  assert.match(validateRegistration('小林', '1234567'), /8/)
  assert.match(validateRegistration('小林', ' '.repeat(10)), /8/)
  assert.match(validateRegistration('小林', '密'.repeat(25)), /72/)
  assert.equal(validateRegistration('小林', '密'.repeat(24)), '')
})

test('storage round trips and survives unavailable or malformed storage', () => {
  const storage = { value: null, getItem() { return this.value }, setItem(key, value) { this.value = value } }
  assert.equal(readWorkspace(storage), null)
  const data = { mode: 'demo', user: null, conversations: [], messages: {} }
  assert.equal(saveWorkspace(data, storage), true)
  assert.deepEqual(readWorkspace(storage), data)
  storage.value = '{invalid'
  assert.equal(readWorkspace(storage), null)
  storage.value = '{"conversations":false}'
  assert.equal(readWorkspace(storage), null)
  const unavailable = { getItem() { throw new Error('disabled') }, setItem() { throw new Error('quota') } }
  assert.equal(readWorkspace(unavailable), null)
  assert.equal(saveWorkspace(data, unavailable), false)
})

test('new conversation titles use the first user question only', () => {
  assert.equal(conversationTitle({ title: '新对话' }), '新对话')
  assert.equal(conversationTitle({ title: '旅行' }, []), '旅行')
  assert.equal(conversationTitle({ title: '新对话' }, [{ role: 'assistant', content: 'reply' }, { role: 'user', content: '帮我做一个学习计划' }]), '帮我做一个学习计划')
  assert.equal(conversationTitle(null, [{ role: 'user', content: 'a'.repeat(30) }]).length, 22)
})

test('all demo branches clearly disclose that no AI was called', () => {
  for (const input of ['工作计划', '写一封邮件', '学习方法', '你好']) {
    assert.match(demoReply(input), /本地演示回复/)
    assert.match(demoReply(input), /不调用 AI/)
  }
  assert.match(demoReply('工作计划'), /10 分钟/)
  assert.match(demoReply('写一封邮件'), /先说明目的/)
  assert.match(demoReply('学习方法'), /费曼/)
})
