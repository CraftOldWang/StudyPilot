import { useEffect, useState } from 'react'
import { learningApi } from '../learningApi'
import type { KnowledgeBase } from '../types'
import type { LearningSession, PlanningView } from '../learningTypes'
import { PilotLogo } from './PilotLogo'

export function NewConversation({ knowledgeBase, onOutline, onCreated }: {
  knowledgeBase: KnowledgeBase; onOutline: () => void; onCreated: (session: LearningSession, message: string) => void
}) {
  const [plan, setPlan] = useState<PlanningView | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    let alive = true
    setLoading(true); setError('')
    learningApi.currentPlan(knowledgeBase.id).then(p => { if (alive) setPlan(p) }).catch(e => { if (alive) setError(e.message) }).finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [knowledgeBase.id, retry])
  async function start() {
    if (!plan || busy || !message.trim()) return
    setBusy(true); setError('')
    try { onCreated(await learningApi.newConversation(plan.id), message.trim()) }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); setBusy(false) }
  }
  return <section className="new-conversation">
    <PilotLogo /><h1>今天，想学些什么？</h1><p>在「{knowledgeBase.name}」里，继续探索。</p>
    {error && <div className="feedback feedback-error" role="alert">{error} <button className="plain" onClick={() => setRetry(v => v + 1)}>重新读取</button></div>}
    {loading ? <p>正在读取学习大纲…</p> : plan?.status === 'SUCCEEDED' ? <>
      <form className="new-composer" onSubmit={e => { e.preventDefault(); void start() }}>
        <label className="visually-hidden" htmlFor="new-message">开始学习或提问</label>
        <textarea id="new-message" value={message} onChange={e => setMessage(e.target.value)} maxLength={12000} placeholder="向 StudyPilot 提问，或从大纲继续学习…" onKeyDown={e => { if (e.ctrlKey && e.key === 'Enter' && !e.nativeEvent.isComposing) { e.preventDefault(); void start() } }} />
        <div className="composer-bottom"><button type="button" className="plain" onClick={onOutline}>☷ 学习大纲</button><button className="send-button" aria-label="发送消息" disabled={busy || !message.trim()}>{busy ? '…' : '↑'}</button></div>
      </form>
      <div className="starter-prompts">{['按大纲继续学习', '先用一个例子解释当前知识点'].map(text => <button className="secondary" key={text} onClick={() => setMessage(text)}>{text}</button>)}</div>
    </> : <div className="outline-onboarding"><p>{plan ? '大纲尚未生成完成，可以继续整理。' : '先用课件生成一份大纲，之后每次对话都能使用。'}</p><button onClick={onOutline}>{plan ? '查看大纲进度' : '建立学习大纲'}</button></div>}
  </section>
}
