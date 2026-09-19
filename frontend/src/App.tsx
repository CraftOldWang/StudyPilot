import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from './api'
import { DocumentPanel } from './components/DocumentPanel'
import { KnowledgeBaseSidebar, type WorkspaceView } from './components/KnowledgeBaseSidebar'
import { LearningPanel } from './components/LearningPanel'
import { PlanningStart } from './components/PlanningStart'
import { NewConversation } from './components/NewConversation'
import { TestTools } from './components/TestTools'
import { LearningMemoryPage } from './components/LearningMemoryPage'
import type { DocumentItem, KnowledgeBase } from './types'
import { useDocumentPolling } from './useDocumentPolling'

export default function App() {
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [documents, setDocuments] = useState<DocumentItem[]>([])
  const [loading, setLoading] = useState(true)
  const [documentsLoading, setDocumentsLoading] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [view, setView] = useState<WorkspaceView>('new')
  const [chat, setChat] = useState<{ id: string; kb: string; message?: string } | null>(null)
  const [revision, setRevision] = useState(0)
  const [creating, setCreating] = useState(false)
  const [name, setName] = useState('')
  const [rename, setRename] = useState(false)
  const selectedRef = useRef(selectedId)
  const request = useRef(0)
  selectedRef.current = selectedId
  const kb = knowledgeBases.find(k => k.id === selectedId)
  const chatKb = knowledgeBases.find(k => k.id === chat?.kb)
  const changed = useCallback(() => setRevision(n => n + 1), [])
  useEffect(() => {
    let alive = true
    api.listKnowledgeBases().then(items => { if (alive) { setKnowledgeBases(items); setSelectedId(items[0]?.id || null) } })
      .catch(e => { if (alive) setError(e.message) }).finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [])
  const refreshDocuments = useCallback(async (silent = false) => {
    if (!selectedId) return
    const current = ++request.current
    if (!silent) setDocumentsLoading(true)
    try {
      const rows = await api.listDocuments(selectedId)
      if (current === request.current && selectedRef.current === selectedId) setDocuments(rows)
    } catch (e) { if (current === request.current) setError(e instanceof Error ? e.message : String(e)) }
    finally { if (current === request.current) setDocumentsLoading(false) }
  }, [selectedId])
  useEffect(() => { setDocuments([]); void refreshDocuments() }, [refreshDocuments])
  useDocumentPolling(selectedId, documents, refreshDocuments)
  useEffect(() => { document.title = `${({ new: '新对话', knowledge: '资料库', outline: '学习大纲', learning: '学习对话', tools: '测试工具', memory: '学习记忆' })[view]} — StudyPilot` }, [view])
  function select(id: string) { selectedRef.current = id; request.current++; setSelectedId(id); setRename(false); setError('') }
  function navigate(next: WorkspaceView) { setView(next); setError('') }
  function openSession(kbId: string, id: string, message?: string) { select(kbId); setChat({ id, kb: kbId, message }); setView('learning'); changed() }
  async function saveLibrary() {
    if (!name.trim() || busy || loading) return
    setBusy(true); setError('')
    try {
      const result = rename && kb ? await api.renameKnowledgeBase(kb.id, name.trim()) : await api.createKnowledgeBase(name.trim())
      setKnowledgeBases(items => rename ? items.map(k => k.id === result.id ? result : k) : [result, ...items])
      select(result.id); setCreating(false); setRename(false); setName('')
    } catch (e) { setError(e instanceof Error ? e.message : String(e)) }
    finally { setBusy(false) }
  }
  const selector = <select aria-label="当前知识库" value={selectedId || ''} onChange={e => select(e.target.value)} disabled={loading || busy}>
    {!kb && <option value="">选择知识库</option>}{knowledgeBases.map(k => <option key={k.id} value={k.id}>{k.name}</option>)}
  </select>
  return <div className="app-shell">
    <KnowledgeBaseSidebar items={knowledgeBases} selectedId={selectedId} activeSessionId={chat?.id || null} loading={loading} view={view} revision={revision}
      onNavigate={navigate} onSelect={id => { select(id); navigate('new') }} onSession={openSession} />
    <main className="app-main">
      {error && <div className="error-banner" role="alert"><span>{error}</span><button className="plain" aria-label="关闭错误" onClick={() => setError('')}>×</button></div>}
      {view === 'new' && <><header className="workspace-header">{selector}{kb && <button className="plain" onClick={() => navigate('outline')}>学习大纲 ↗</button>}</header>
        {kb ? <NewConversation key={kb.id} knowledgeBase={kb} onOutline={() => navigate('outline')} onCreated={(s, message) => openSession(kb.id, s.id, message)} />
          : <section className="new-conversation"><h1>{loading ? '正在打开学习空间…' : '从一份课件开始'}</h1><p>创建知识库，整理资料，开始你的学习对话。</p><button disabled={loading} onClick={() => { setCreating(true); navigate('knowledge') }}>创建知识库</button></section>}</>}
      <div className="page-scroll" hidden={view !== 'knowledge'}>
        <header className="page-heading"><div><h1>资料库</h1><p>课程资料，都在这里。</p></div><button onClick={() => { setCreating(true); setRename(false); setName('') }}>＋ 新建知识库</button></header>
        <div className="library-toolbar">{selector}{kb && <><button className="plain" onClick={() => { setName(kb.name); setRename(true); setCreating(false) }}>重命名</button><button className="plain" onClick={() => navigate('outline')}>学习大纲 ↗</button></>}</div>
        {(creating || rename) && <form className="library-create" onSubmit={e => { e.preventDefault(); void saveLibrary() }}>
          <label htmlFor="library-name">{rename ? '知识库名称' : '新知识库名称'}</label><input id="library-name" autoFocus value={name} maxLength={100} onChange={e => setName(e.target.value)} placeholder="例如：操作系统" />
          <button disabled={loading || busy || !name.trim()}>{busy ? '保存中…' : '保存'}</button><button type="button" className="plain" disabled={busy} onClick={() => { setCreating(false); setRename(false) }}>取消</button>
        </form>}
        {kb && <DocumentPanel key={kb.id} documents={documents} knowledgeBase={kb} loading={documentsLoading} onUploaded={id => { if (selectedRef.current === id) void refreshDocuments(true) }} />}
      </div>
      {view === 'outline' && kb && <div className="page-scroll"><PlanningStart key={kb.id} knowledgeBase={kb} visible requestedSessionId={null} onSession={async s => openSession(kb.id, s.id)} /></div>}
      <div className="active-chat-view" hidden={view !== 'learning'}>
        {chat && chatKb && <LearningPanel key={chat.id} initialSessionId={chat.id} initialMessage={chat.message} knowledgeBase={chatKb} onChanged={changed}
          onBack={() => navigate('new')} onOutline={() => { select(chat.kb); navigate('outline') }} onSessionKnowledgeBase={() => {}} />}
      </div>
      {view === 'tools' && <TestTools knowledgeBases={knowledgeBases} selectedId={selectedId} onSelect={select} />}
      <div hidden={view !== 'memory'} className="memory-view"><LearningMemoryPage knowledgeBases={knowledgeBases} selectedId={selectedId} visible={view === 'memory'} onSelect={select} onSession={openSession} /></div>
    </main>
  </div>
}
