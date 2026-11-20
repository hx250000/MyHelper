const STORAGE_KEY = 'myhelper.workspace.v1'

export function readWorkspace(storage = localStorage) {
  try {
    const data = JSON.parse(storage.getItem(STORAGE_KEY))
    if (!data || !Array.isArray(data.conversations) || !data.messages || typeof data.messages !== 'object') return null
    return data
  } catch {
    return null
  }
}

export function saveWorkspace(data, storage = localStorage) {
  try {
    storage.setItem(STORAGE_KEY, JSON.stringify(data))
    return true
  } catch {
    return false
  }
}

export function validateRegistration(username, password) {
  if (!username.trim()) return '请输入用户名。'
  if (username.trim().length > 64) return '用户名不能超过 64 个字符。'
  if (!password.trim() || [...password].length < 8) return '密码至少需要 8 个字符。'
  if (new TextEncoder().encode(password).length > 72) return '密码不能超过 72 个 UTF-8 字节。'
  return ''
}

export function conversationTitle(conversation, messages = []) {
  if (conversation?.title && conversation.title !== '新对话') return conversation.title
  const firstQuestion = messages.find((message) => message.role === 'user')?.content
  return firstQuestion ? firstQuestion.slice(0, 22) : '新对话'
}

export function demoReply(content) {
  let answer
  if (/计划|安排|效率|待办/.test(content)) {
    answer = '可以先把目标拆成三个小步骤：\n\n1. 写下今天最重要的一件事，为它留出一段不被打扰的时间。\n2. 把剩余任务分成「必须完成」和「可以延后」，一次只处理一件。\n3. 在一天结束前留出 10 分钟回顾，记录进展并安排明天。\n\n你可以补充目标、时间和限制条件，再到后端模式获取 AI 为你定制的计划。'
  } else if (/写|文案|邮件|表达/.test(content)) {
    answer = '写作可以从一个简单框架开始：\n\n先说明目的 → 补充必要背景 → 提出具体行动。\n\n例如：\n「你好，想和你确认一下本周的项目进展。目前初稿已经完成，希望在周五前收到你的反馈。方便的话，我们可以约 15 分钟一起讨论。谢谢！」\n\n连接后端后，你可以提供收件人、语气和具体内容，让 AI 帮你进一步润色。'
  } else if (/学习|解释|知识|概念/.test(content)) {
    answer = '试试用「费曼学习法」理解一个新概念：\n\n1. 用自己的话解释它，假设听众完全没有相关背景。\n2. 找出解释时卡住的地方，回到资料补充理解。\n3. 用一个日常例子重新说明，再把表达变得更简洁。\n\n连接后端后，告诉 AI 你想学什么，它就能围绕具体主题展开。'
  } else {
    answer = '收到你的想法了。我们可以先明确三个问题：\n\n• 你想达成什么结果？\n• 目前最需要解决的困难是什么？\n• 有哪些时间或资源限制？\n\n把这些信息补充完整，就更容易找到下一步的方向。连接后端服务后，即可获得真正的 AI 回复。'
  }
  return `${answer}\n\n—— 本地演示回复，仅展示交互，不调用 AI。`
}
