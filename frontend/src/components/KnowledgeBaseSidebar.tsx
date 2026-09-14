import { useEffect, useState } from 'react'
import { learningApi } from '../learningApi'
import type { KnowledgeBase } from '../types'
import type { SessionEntry } from '../learningTypes'
import { PilotLogo } from './PilotLogo'

export type WorkspaceView = 'new' | 'knowledge' | 'outline' | 'learning' | 'tools'
interface Props {
  items: KnowledgeBase[]; selectedId: string | null; activeSessionId: string | null
  loading: boolean; view: WorkspaceView; revision: number
  onNavigate: (view: WorkspaceView) => void; onSelect: (id: string) => void; onSession: (kb: string, id: string) => void
}
function CourseChats({ item, selected, activeSessionId, revision, onSelect, onSession }: {
  item: KnowledgeBase; selected: boolean; activeSessionId: string | null; revision: number
  onSelect: () => void; onSession: (id: string) => void
}) {
  const [open, setOpen] = useState(selected)
  const [sessions, setSessions] = useState<SessionEntry[]>([])
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [limit, setLimit] = useState(5)
  const [retry, setRetry] = useState(0)
  useEffect(() => { if (selected) setOpen(true) }, [selected])
  useEffect(() => {
    if (!open) return
    let alive = true
    setLoading(true); setError('')
    learningApi.listSessions(item.id).then(rows => { if (alive) setSessions(rows) })
      .catch(e => { if (alive) setError(e.message) }).finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [item.id, open, revision, retry])
  return <li className="course-group">
    <div className={`course-heading${selected ? ' selected' : ''}`}>
      <button className="plain course-expand" aria-label={`${open ? '收起' : '展开'} ${item.name}`} aria-expanded={open} onClick={() => setOpen(!open)}><svg viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="m7.5 5 5 5-5 5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" /></svg></button>
      <button className="plain course-name" title={item.name} onClick={onSelect}><svg className="course-icon" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M3 6V4.5h5L10 7h7v9H3V6Z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" /></svg><span className="course-label">{item.name}</span></button>
    </div>
    {open && <ul className="course-chats">
      {sessions.slice(0, limit).map(s => <li key={s.id}><button className={`plain chat-link${activeSessionId === s.id ? ' active' : ''}`} title={s.learningGoal} onClick={() => onSession(s.id)}>{s.learningGoal}</button></li>)}
      {loading && !sessions.length && <li className="sidebar-note">读取对话…</li>}
      {!loading && !sessions.length && !error && <li className="sidebar-note">还没有对话</li>}
      {error && <li><button className="plain sidebar-note" title={error} onClick={() => setRetry(v => v + 1)}>读取失败，重试</button></li>}
      {sessions.length > limit && <li><button className="plain sidebar-note" onClick={() => setLimit(n => n + 10)}>显示更多</button></li>}
    </ul>}
  </li>
}
export function KnowledgeBaseSidebar({ items, selectedId, activeSessionId, loading, view, revision, onNavigate, onSelect, onSession }: Props) {
  return <aside className="sidebar">
    <button className="brand plain" onClick={() => onNavigate('new')} aria-label="StudyPilot 首页"><PilotLogo /><strong>StudyPilot</strong></button>
    <nav className="global-nav" aria-label="主导航">
      <button className={`plain${view === 'new' ? ' active' : ''}`} onClick={() => onNavigate('new')}><span aria-hidden="true">＋</span>新对话</button>
      <button className={`plain${view === 'knowledge' ? ' active' : ''}`} onClick={() => onNavigate('knowledge')}><span aria-hidden="true">▤</span>资料库</button>
      <button className={`plain${view === 'tools' ? ' active' : ''}`} onClick={() => onNavigate('tools')}><span aria-hidden="true">⚙</span>测试工具</button>
    </nav>
    <div className="sidebar-heading">我的知识库</div>
    <div className="sidebar-scroll">
      {loading ? <p className="sidebar-note">读取资料库…</p> : <ul className="course-list">{items.map(item => <CourseChats key={item.id} item={item} selected={selectedId === item.id}
        activeSessionId={view === 'learning' ? activeSessionId : null} revision={revision} onSelect={() => onSelect(item.id)} onSession={id => onSession(item.id, id)} />)}</ul>}
      {!loading && !items.length && <button className="plain sidebar-note" onClick={() => onNavigate('knowledge')}>创建第一个知识库</button>}
    </div>
    <div className="sidebar-footer"><span className="profile-mark">S</span><div>我的学习空间<small>StudyPilot · 本地演示</small></div></div>
  </aside>
}
