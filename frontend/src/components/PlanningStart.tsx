import { type FormEvent, useEffect, useRef, useState } from 'react'
import { api } from '../api'
import { learningApi } from '../learningApi'
import type { LearningSession, PlanningView } from '../learningTypes'
import type { DocumentItem, KnowledgeBase } from '../types'
import { Feedback } from './ui/Feedback'
import { Field, MultilineInput } from './ui/Field'
import { LearningOutline, outlineLeafCount } from './LearningOutline'

type Role = 'lesson' | 'exercise' | 'unused'
const drafts = new Map<string, { roles: Record<string, Role>; goal: string }>()
const phaseLabel = (value: string) => ({ EXTRACT: '读取课件', OUTLINE: '整理目录', EMPHASIS: '标注重点', EMPHASIS_REVIEW: '整理重点', TASKS: '保存学习大纲' }[value.split('/')[0]] || '正在整理大纲')

export function PlanningStart({ knowledgeBase, visible, requestedSessionId, onSession }: {
  knowledgeBase: KnowledgeBase; visible: boolean; requestedSessionId: string | null
  onSession: (session: LearningSession) => Promise<void>
}) {
  const [documents, setDocuments] = useState<DocumentItem[]>([])
  const [roles, setRoles] = useState<Record<string, Role>>(() => drafts.get(knowledgeBase.id)?.roles || {})
  const [goal, setGoal] = useState(() => drafts.get(knowledgeBase.id)?.goal || '')
  const [plan, setPlan] = useState<PlanningView | null>(null)
  const [session, setSession] = useState<LearningSession | null>(null)
  const [creating, setCreating] = useState(false)
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const mounted = useRef(true)
  const lock = useRef(false)
  useEffect(() => { drafts.set(knowledgeBase.id, { roles, goal }) }, [knowledgeBase.id, roles, goal])
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  useEffect(() => { if (visible) void reload() }, [visible, requestedSessionId])
  useEffect(() => {
    if (plan?.status !== 'RUNNING') return
    let active = true, fetching = false
    const timer = setInterval(async () => {
      if (fetching) return
      fetching = true
      try {
        const next = await learningApi.getPlan(plan.id)
        if (active) setPlan(next)
      } catch (e) { if (active) setError(e instanceof Error ? e.message : String(e)) }
      finally { fetching = false }
    }, 2500)
    return () => { active = false; clearInterval(timer) }
  }, [plan?.id, plan?.status])
  async function reload() {
    if (lock.current || (creating && !requestedSessionId)) return
    setLoading(true); setError('')
    try {
      const saved = await learningApi.currentPlan(knowledgeBase.id, requestedSessionId)
      const progress = saved?.sessionId ? await learningApi.getSession(saved.sessionId) : null
      if (mounted.current) { setPlan(saved || null); setSession(progress); setCreating(false) }
    } catch (e) { if (mounted.current) setError(e instanceof Error ? e.message : String(e)) }
    finally { if (mounted.current) setLoading(false) }
  }
  async function newOutline() {
    setCreating(true); setError(''); setLoading(true)
    try { const items = await api.listDocuments(knowledgeBase.id); if (mounted.current) setDocuments(items) }
    catch (e) { if (mounted.current) setError(e instanceof Error ? e.message : String(e)) }
    finally { if (mounted.current) setLoading(false) }
  }
  async function action(work: () => Promise<void>) {
    if (lock.current) return
    lock.current = true; setBusy(true); setError('')
    try { await work() }
    catch (e) { if (mounted.current) setError(e instanceof Error ? e.message : String(e)) }
    finally { lock.current = false; if (mounted.current) setBusy(false) }
  }
  async function execute(id: string) {
    setPlan(current => current && { ...current, status: 'RUNNING', errorMessage: null })
    const next = await learningApi.executePlan(id)
    if (mounted.current) setPlan(next)
  }
  const lessons = documents.filter(d => d.pipelineStatus === 'INDEXED' && roles[d.id] === 'lesson').map(d => d.id)
  const exercises = documents.filter(d => d.pipelineStatus === 'INDEXED' && roles[d.id] === 'exercise').map(d => d.id)
  async function generate(event: FormEvent) {
    event.preventDefault()
    if (!goal.trim() || !lessons.length) return
    await action(async () => {
      const created = await learningApi.createPlan(knowledgeBase.id, goal.trim(), lessons, exercises)
      if (!mounted.current) return
      setPlan(created); setSession(null); setCreating(false)
      await execute(created.id)
    })
  }
  const nodes = plan?.result?.nodes || []
  const completed = plan?.completedNodeIds?.length || 0
  const generating = busy || plan?.status === 'RUNNING'
  return <section className="panel outline-page">
    <header className="outline-page-heading"><div><h1>学习大纲</h1><p>{knowledgeBase.name}</p></div>
      {!creating && <button type="button" className="secondary" disabled={generating || loading} onClick={() => void newOutline()}>{plan ? '重新生成' : '建立大纲'}</button>}
      {creating && <button type="button" className="secondary" disabled={busy} onClick={() => setCreating(false)}>返回大纲</button>}
    </header>
    {error && <Feedback error>{error}<button className="text-button" type="button" disabled={busy} onClick={() => void (creating ? newOutline() : reload())}>重新读取</button></Feedback>}
    {loading && <Feedback>正在读取…</Feedback>}
    {!creating && !plan && !loading && !error && <Feedback>还没有当前大纲。选择课件生成一次，之后按目录持续学习。</Feedback>}
    {creating && <form noValidate onSubmit={generate} className="plan-form">
      <Field id="learning-goal" label="课程学习目标"><MultilineInput id="learning-goal" rows={2} value={goal} maxLength={3000} onChange={e => setGoal(e.target.value)} placeholder="例如：系统学习 Java 并发，准备面试" /></Field>
      <fieldset className="source-selection"><legend>选择资料及用途</legend><p>课件生成目录，习题用于标注重点。</p>
        {!loading && !documents.length ? <Feedback>请先在资料库上传课件。</Feedback> : documents.map(doc => <fieldset key={doc.id} className="source-row" disabled={busy || doc.pipelineStatus !== 'INDEXED'}>
          <legend>{doc.title}</legend><div className="source-roles">{(['lesson', 'exercise', 'unused'] as Role[]).map(role => <label key={role}><input type="radio" name={`role-${doc.id}`} checked={(roles[doc.id] || 'unused') === role} onChange={() => setRoles(r => ({ ...r, [doc.id]: role }))} />{role === 'lesson' ? '课件' : role === 'exercise' ? '习题参考' : '不使用'}</label>)}{doc.pipelineStatus !== 'INDEXED' && <small>尚未处理完成</small>}</div>
        </fieldset>)}
      </fieldset>
      <button disabled={busy || loading || !!error || !goal.trim() || !lessons.length} type="submit">生成学习大纲</button>
    </form>}
    {!creating && plan && <>
      <div className="outline-caption"><div><h2>{plan.learningGoal}</h2>{plan.result && <p>{completed} / {outlineLeafCount(nodes)} 已完成</p>}</div>
        {plan.status === 'SUCCEEDED' && <button disabled={busy} type="button" onClick={() => void action(async () => {
          const value = await learningApi.planSession(plan.id)
          if (mounted.current) { setSession(value); setPlan(current => current && { ...current, sessionId: value.id }) }
          await onSession(value)
        })}>{session ? session.status === 'COMPLETED' ? '查看学习记录' : '继续学习' : '开始学习'}</button>}
      </div>
      {plan.errorMessage && <Feedback error>{plan.errorMessage}</Feedback>}
      {plan.status !== 'SUCCEEDED' && <div className="action-row"><span>{generating ? phaseLabel(plan.stages.at(-1)?.stage || '') : '大纲尚未生成完成'}</span><button disabled={generating} type="button" onClick={() => void action(() => execute(plan.id))}>{generating ? '生成中…' : '继续生成'}</button></div>}
      {plan.result && <LearningOutline nodes={nodes} points={session?.plan || []} completedNodeIds={plan.completedNodeIds} />}
    </>}
  </section>
}
