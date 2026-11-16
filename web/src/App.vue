<script setup>
import { ref, nextTick } from 'vue'
import { marked } from 'marked'

marked.setOptions({ breaks: true, gfm: true })

const messages = ref([])
const input = ref('')
const busy = ref(false)
const conversationId = ref('web-' + Math.random().toString(36).slice(2, 10))
const listEl = ref(null)
const inputEl = ref(null)

const EXAMPLES = [
  '研究生学业奖学金的奖励标准是多少？',
  '补考没过还能重修几次？',
  '国家励志奖学金需要什么条件？',
  '第二课堂要修满多少学分才能毕业？',
]

const DECISION_TEXT = {
  rag: '依据手册',
  direct: '直接回答',
  refused: '超出范围',
  no_answer: '手册未收录',
  error: '请求失败',
}

function shortSource(source) {
  if (!source) return ''
  const name = String(source).split(/[\\/]/).pop().replace(/\.md$/i, '')
  const match = name.match(/^(\d+)_(.+)$/)
  if (!match) return name
  const title = match[2]
    .replace(/_(修订|修订版|试行|2018年修订稿|2020年修订版)_?$/, '')
    .replace(/_/g, '')
  return `${match[1]} · ${title}`
}

async function send(text) {
  const question = (text ?? input.value).trim()
  if (!question || busy.value) return

  messages.value.push({ role: 'user', text: question })
  input.value = ''
  busy.value = true
  resize()
  await scrollDown()

  try {
    const response = await fetch('/api/v2/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        message: question,
        conversationId: conversationId.value,
        useRag: true,
      }),
    })
    if (!response.ok) throw new Error('HTTP ' + response.status)
    // A session that expired mid-conversation comes back as a redirect to the login page.
    if (response.redirected) {
      location.href = '/login.html'
      return
    }
    const data = await response.json()
    messages.value.push({
      role: 'assistant',
      html: marked.parse(data.content || ''),
      decision: data.decision || 'rag',
      sources: data.sources || [],
    })
  } catch (error) {
    messages.value.push({
      role: 'assistant',
      text: '请求失败：' + error.message,
      decision: 'error',
      error: true,
    })
  } finally {
    busy.value = false
    await scrollDown()
  }
}

async function scrollDown() {
  await nextTick()
  if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight
}

async function logout() {
  try {
    await fetch('/logout', { method: 'POST' })
  } finally {
    location.href = '/login.html'
  }
}

function resize() {
  const el = inputEl.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 140) + 'px'
}
</script>

<template>
  <div class="app">
    <header class="topbar">
      <div class="brand">
        <span class="dot" :class="{ live: !busy }"></span>
        <h1>学生手册问答</h1>
      </div>
      <span class="sub">贵州财经大学 · 只依据手册原文</span>
      <button class="logout" @click="logout">退出</button>
    </header>

    <main ref="listEl" class="messages">
      <div v-if="!messages.length" class="empty">
        <p class="lead">想了解哪条规定？</p>
        <div class="examples">
          <button v-for="q in EXAMPLES" :key="q" class="example" @click="send(q)">{{ q }}</button>
        </div>
      </div>

      <div v-for="(m, i) in messages" :key="i" :class="['msg', m.role]">
        <div class="bubble">
          <div v-if="m.html" class="md" v-html="m.html"></div>
          <p v-else>{{ m.text }}</p>
        </div>

        <div v-if="m.role === 'assistant'" class="meta">
          <span :class="['badge', m.decision]">{{ DECISION_TEXT[m.decision] || m.decision }}</span>
          <template v-if="m.decision === 'rag'">
            <span v-for="s in m.sources" :key="s" class="chip" :title="s">{{ shortSource(s) }}</span>
          </template>
          <template v-else-if="m.decision === 'no_answer' && m.sources.length">
            <span class="chip muted">可能相关：{{ m.sources.join(' / ') }}</span>
          </template>
        </div>
      </div>

      <div v-if="busy" class="msg assistant">
        <div class="bubble typing"><i></i><i></i><i></i></div>
      </div>
    </main>

    <footer class="composer-wrap">
      <div class="composer">
        <textarea
          ref="inputEl"
          v-model="input"
          rows="1"
          placeholder="问手册里的规定，例如：重修最多几次？"
          @keydown.enter.exact.prevent="send()"
          @input="resize"
        ></textarea>
        <button :disabled="busy || !input.trim()" @click="send()">
          <span v-if="!busy">发送</span>
          <span v-else>…</span>
        </button>
      </div>
      <p class="hint">回答仅依据学生手册，可能不完整；重要事项以原文和辅导员答复为准。</p>
    </footer>
  </div>
</template>