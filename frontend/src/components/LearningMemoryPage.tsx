import { useEffect, useRef, useState } from 'react'
import { memoryApi, type LearningMemory, type LearningPreferences } from '../memoryApi'
import type { KnowledgeBase } from '../types'
import { Field, MultilineInput } from './ui/Field'
import { Feedback } from './ui/Feedback'

export function LearningMemoryPage({ knowledgeBases, selectedId, visible, onSelect, onSession }: {
  knowledgeBases: KnowledgeBase[]; selectedId: string | null; onSelect: (id: string) => void
  visible: boolean
  onSession: (kb: string, id: string) => void
}) {
  const [preferences, setPreferences] = useState<LearningPreferences | null>(null)
  const [records, setRecords] = useState<LearningMemory[]>([])
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [changing, setChanging] = useState<string | null>(null)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [retry, setRetry] = useState(0)
  const currentKb = useRef(selectedId)
  currentKb.current = selectedId
  useEffect(() => {
    if (preferences) return
    let alive = true
    memoryApi.preferences().then(value => { if (alive) setPreferences(value) })
      .catch(e => { if (alive) setError(e.message) })
    return () => { alive = false }
  }, [retry])
  useEffect(() => {
    if (!visible) return
    let alive = true
    setRecords([]); setLoading(true); setError('')
    if (!selectedId) { setLoading(false); return }
    memoryApi.list(selectedId).then(rows => { if (alive) setRecords(rows) })
      .catch(e => { if (alive) setError(e.message) }).finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [selectedId, retry, visible])
  async function save() {
    if (!preferences || saving) return
    setSaving(true); setError(''); setMessage('')
    try { setPreferences(await memoryApi.save(preferences)); setMessage('学习偏好已保存，后续消息会使用新设置。') }
    catch (e) { setError(e instanceof Error ? e.message : String(e)) }
    finally { setSaving(false) }
  }
  async function toggle(row: LearningMemory) {
    if (!selectedId || changing) return
    const kb = selectedId
    setChanging(row.id); setError(''); setMessage('')
    try {
      await memoryApi.include(kb, row.id, !row.included)
      if (currentKb.current === kb) {
        setRecords(items => items.map(item => item.id === row.id ? { ...item, included: !item.included } : item))
        setMessage(row.included ? '已排除此记录，之后不再作为跨会话记忆提供给模型。原聊天仍保留。' : '已重新纳入此记录。')
      }
    } catch (e) { if (currentKb.current === kb) setError(e instanceof Error ? e.message : String(e)) }
    finally { setChanging(null) }
  }
  return <div className="page-scroll memory-page">
    <header className="page-heading"><div><h1>学习记忆</h1><p>让新的对话了解你的偏好，记得之前练习过什么。</p></div></header>
    {error && <Feedback error>{error} <button className="plain" onClick={() => setRetry(n => n + 1)}>重新读取</button></Feedback>}
    {message && <Feedback>{message}</Feedback>}
    {preferences ? <form className="memory-preferences" onSubmit={e => { e.preventDefault(); void save() }}>
      <h2>我的学习偏好</h2>
      <Field id="memory-style" label="希望怎样讲解" hint="适用于所有知识库，例如：先用生活类比，再给出定义和步骤。">
        <MultilineInput id="memory-style" aria-describedby="memory-style-hint" maxLength={300} rows={3} disabled={saving}
          value={preferences.explanationStyle} onChange={e => setPreferences({ ...preferences, explanationStyle: e.target.value })} />
      </Field>
      <Field id="memory-goal" label="长期学习目标">
        <MultilineInput id="memory-goal" maxLength={500} rows={3} disabled={saving} placeholder="例如：准备期末考试，优先理解原理与典型题。"
          value={preferences.learningGoal} onChange={e => setPreferences({ ...preferences, learningGoal: e.target.value })} />
      </Field>
      <label className="memory-toggle"><input type="checkbox" checked={preferences.memoryEnabled} disabled={saving}
        onChange={e => setPreferences({ ...preferences, memoryEnabled: e.target.checked })} />启用跨会话学习记忆</label>
      <p className="muted">关闭后不再记录新测验，也不再注入偏好和历史记录。已保存记录与原聊天保留。</p>
      <button disabled={saving}>{saving ? '保存中…' : '保存偏好'}</button>
    </form> : !error && <p role="status">正在读取学习偏好…</p>}
    <section className="memory-history" aria-busy={loading}>
      <div className="memory-history-heading"><div><h2>练习留下的记录</h2><p className="muted">最近 50 次测验，仅在当前知识库内使用。结果依据生成题目的标准答案，不代表长期掌握程度。</p></div>
        <select aria-label="学习记忆的知识库" value={selectedId || ''} onChange={e => onSelect(e.target.value)}>
          {!selectedId && <option value="">选择知识库</option>}{knowledgeBases.map(kb => <option key={kb.id} value={kb.id}>{kb.name}</option>)}
        </select>
      </div>
      {loading ? <p role="status">正在读取测验记录…</p> : !records.length && !error ? <p className="memory-empty">{selectedId ? '还没有学习记忆。开启记忆后提交一次测验，就会在这里留下记录。历史测验不会自动补录。' : '创建知识库后，这里会展示该课程的测验记录。'}</p> : null}
      <ul className="memory-records">{records.map(row => <li key={row.id} className={row.included ? '' : 'memory-excluded'}>
        <div className="memory-record-heading"><strong>{row.topic}</strong><span>答对 {row.correctCount} / {row.questionCount} 题</span></div>
        <small>{row.recordedAt.replace('T', ' ')}{!row.included && ' · 已排除'}</small>
        {row.mistakes.length > 0 ? <details><summary>查看当次错题（{row.mistakes.length}）</summary><ul>{row.mistakes.map((item, index) => <li key={index}>{item.question}</li>)}</ul></details> : <p className="muted">本次测验全部答对。</p>}
        <div className="action-row"><button className="plain" onClick={() => selectedId && onSession(selectedId, row.sessionId)}>查看原对话</button>
          <button className="plain" disabled={changing !== null} onClick={() => void toggle(row)}>{changing === row.id ? '保存中…' : row.included ? '不再用于后续对话' : '重新纳入记忆'}</button></div>
      </li>)}</ul>
    </section>
  </div>
}
