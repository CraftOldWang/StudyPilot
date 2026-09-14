import { useEffect, useState } from 'react'
import { apiRequest, ApiError } from '../api'
import { learningApi } from '../learningApi'
import type { KnowledgeBase } from '../types'
import type { ConversationTurn, SessionEntry } from '../learningTypes'
import { MessageContent } from './ui/MessageContent'

const tools = { search: '普通检索', agent: 'Agent 检索', trace: 'Trace 查看', eval: '评测', hello: 'Hello 诊断' }
type Tool = keyof typeof tools
type Result = { answer?: string; message?: string; hits?: { chunkId: string; content: string; score: number; provenance?: { documentTitle?: string } }[] }
export function TestTools({ knowledgeBases, selectedId, onSelect }: {
  knowledgeBases: KnowledgeBase[]; selectedId: string | null; onSelect: (id: string) => void
}) {
  const [tool, setTool] = useState<Tool>('search')
  const [query, setQuery] = useState('')
  const [mode, setMode] = useState('VECTOR')
  const [topK, setTopK] = useState(5)
  const [evaluation, setEvaluation] = useState('context')
  const [strategy, setStrategy] = useState('LOCAL')
  const [sessions, setSessions] = useState<SessionEntry[]>([])
  const [sessionId, setSessionId] = useState('')
  const [turns, setTurns] = useState<ConversationTurn[]>([])
  const [traceId, setTraceId] = useState('')
  const [systemPrompt, setSystemPrompt] = useState('请根据提供的内容回答问题。')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [result, setResult] = useState<unknown>(null)
  const [elapsed, setElapsed] = useState(0)
  useEffect(() => {
    setResult(null); setError(''); setSessionId(''); setTurns([]); setTraceId(''); setSessions([])
    if (!selectedId || !['trace', 'eval'].includes(tool)) return
    let alive = true
    learningApi.listSessions(selectedId).then(rows => { if (alive) setSessions(rows) }).catch(e => { if (alive) setError(e.message) })
    return () => { alive = false }
  }, [selectedId, tool])
  useEffect(() => {
    setTurns([]); setTraceId('')
    if (!sessionId || tool !== 'trace') return
    let alive = true
    learningApi.history(sessionId).then(rows => { if (alive) setTurns(rows) }).catch(e => { if (alive) setError(e.message) })
    return () => { alive = false }
  }, [sessionId, tool])
  async function execute() {
    if (busy) return
    setBusy(true); setError(''); setResult(null)
    const start = performance.now()
    try {
      let path = '', body: unknown, method = 'POST'
      if (tool === 'search' || tool === 'agent') { path = `/api/knowledge-bases/${selectedId}/${tool === 'search' ? 'search' : 'agent-search'}`; body = { query, mode, topK } }
      else if (tool === 'trace') { path = `/api/learning/traces/${encodeURIComponent(traceId.trim())}`; method = 'GET' }
      else if (tool === 'hello') path = '/api/agent/hello'
      else if (evaluation === 'context') { path = `/api/eval/knowledge-bases/${selectedId}/context-comparison`; body = { query, topK } }
      else if (evaluation === 'completion') { path = '/api/eval/completions'; body = { purpose: 'manual-ui', promptVersion: 'v1', systemPrompt, prompt: query, maxTokens: 3000 } }
      else { path = `/api/eval/learning/sessions/${sessionId}/${evaluation}`; body = evaluation === 'compression' ? { strategy } : undefined }
      setResult(await apiRequest(path, { method, body: body === undefined ? undefined : JSON.stringify(body) }))
    } catch (e) {
      setError(e instanceof ApiError && e.status === 404 && tool === 'eval' ? '评测接口不可用：请确认后端已启用对应的 eval / local 配置。' : e instanceof Error ? e.message : String(e))
    } finally { setElapsed(Math.round(performance.now() - start)); setBusy(false) }
  }
  const needsQuery = tool === 'search' || tool === 'agent' || (tool === 'eval' && ['context', 'completion'].includes(evaluation))
  const needsSession = tool === 'trace' || (tool === 'eval' && ['compression', 'replica'].includes(evaluation))
  const needsKb = tool === 'search' || tool === 'agent' || (tool === 'eval' && evaluation === 'context')
  const data = result as Result | null
  return <div className="test-page">
    <header className="page-heading"><div><h1>测试工具</h1><p>检索、调用记录与实验，集中在这里。</p></div></header>
    <div className="test-workspace"><nav className="test-nav" aria-label="测试业务">{Object.entries(tools).map(([id, name]) => <button disabled={busy} className={`plain${tool === id ? ' active' : ''}`} key={id} aria-current={tool === id ? 'page' : undefined} onClick={() => setTool(id as Tool)}>{name}</button>)}</nav>
      <section className="test-content"><h2>{tools[tool]}</h2>
        <form className="test-form" onSubmit={e => { e.preventDefault(); void execute() }}>
          <fieldset disabled={busy}>
            {tool !== 'hello' && <label>知识库<select value={selectedId || ''} onChange={e => onSelect(e.target.value)}><option value="" disabled>选择知识库</option>{knowledgeBases.map(k => <option value={k.id} key={k.id}>{k.name}</option>)}</select></label>}
            {tool === 'eval' && <label>评测内容<select value={evaluation} onChange={e => { setEvaluation(e.target.value); setResult(null); setError('') }}><option value="context">子块 / 父块上下文对照</option><option value="replica">创建学习实验副本</option><option value="compression">设置会话压缩策略</option><option value="completion">模型回答评测</option></select></label>}
            {needsSession && <label>学习会话<select value={sessionId} onChange={e => setSessionId(e.target.value)}><option value="">选择会话</option>{sessions.map(s => <option value={s.id} key={s.id}>{s.learningGoal} · {s.updatedAt}</option>)}</select></label>}
            {tool === 'trace' && <><label>对话回合<select value={traceId} onChange={e => setTraceId(e.target.value)}><option value="">选择回合，或下方输入 traceId</option>{turns.map(t => <option value={t.traceId} key={t.id}>{t.userMessage.slice(0, 50)}</option>)}</select></label><label>traceId<input value={traceId} onChange={e => setTraceId(e.target.value)} placeholder="调用追踪编号" /></label></>}
            {tool === 'search' && <label>检索策略<select value={mode} onChange={e => setMode(e.target.value)}>{['BM25', 'VECTOR', 'RRF', 'PARENT'].map(m => <option key={m}>{m}</option>)}</select></label>}
            {(tool === 'search' || (tool === 'eval' && evaluation === 'context')) && <label>Top K<input type="number" min={1} max={20} value={topK} onChange={e => setTopK(Number(e.target.value))} /></label>}
            {tool === 'eval' && evaluation === 'compression' && <label>压缩策略<select value={strategy} onChange={e => setStrategy(e.target.value)}>{['NONE', 'THRESHOLD', 'LOCAL'].map(s => <option key={s}>{s}</option>)}</select></label>}
            {tool === 'eval' && evaluation === 'completion' && <label>系统提示词<textarea value={systemPrompt} maxLength={8000} onChange={e => setSystemPrompt(e.target.value)} /></label>}
            {needsQuery && <label>{evaluation === 'completion' && tool === 'eval' ? '评测输入' : '查询内容'}<textarea value={query} onChange={e => setQuery(e.target.value)} placeholder="输入要测试的问题…" required /></label>}
            {tool === 'hello' && <p>发送一次 Hello 请求，查看 Agent 模型调用是否正常。</p>}
            {tool === 'eval' && evaluation === 'replica' && <p className="muted">将创建独立实验会话，不复制原会话消息或改写原学习进度。</p>}
            {tool === 'eval' && evaluation === 'compression' && <p className="muted">此操作会修改所选会话的压缩策略，建议选择实验副本。</p>}
          </fieldset>
          <button disabled={busy || (needsQuery && !query.trim()) || (needsKb && !selectedId) || (tool === 'trace' && !traceId.trim()) || (needsSession && tool !== 'trace' && !sessionId)}>{busy ? '执行中…' : tool === 'eval' && evaluation === 'replica' ? '创建副本' : tool === 'eval' && evaluation === 'compression' ? '应用策略' : '执行'}</button>
        </form>
        {error && <div className="feedback feedback-error" role="alert">{error}</div>}
        {result !== null && <div className="test-result"><div className="result-title"><h3>执行结果</h3><small>{elapsed} ms</small></div>
          {typeof result === 'string' && <MessageContent text={result} />}
          {data?.answer && <MessageContent text={data.answer} />}
          {data?.message && <p>{data.message}</p>}
          {data?.hits?.length === 0 && <p className="muted">没有返回相关资料片段。</p>}
          {data?.hits?.map(hit => <article className="retrieval-hit" key={hit.chunkId}><strong>{hit.provenance?.documentTitle || '资料片段'}</strong><small>分数 {hit.score} · {hit.chunkId}</small><p>{hit.content}</p></article>)}
          {tool === 'trace' && Array.isArray(result) && <ol className="trace-list">{result.map((event, i) => <li key={i}><strong>{event.stage} · {event.status}</strong><p>{event.summary}</p><small>{event.createdAt}</small></li>)}</ol>}
          <details open={tool === 'eval'}><summary>完整响应</summary><pre>{JSON.stringify(result, null, 2)}</pre></details>
        </div>}
      </section></div>
  </div>
}
