import { createContext, type ReactNode, useContext, useEffect, useRef, useState } from 'react'
import { apiRequest } from '../api'
import { Feedback } from './ui/Feedback'

interface Source { chunkId: string; documentId: string; documentTitle: string; sourceLocation: string; content: string }
const SourceContext = createContext<((chunkId: string) => void) | null>(null)

export function locationLabel(raw: string) {
  if (!raw?.startsWith('{')) return raw || '资料位置未记录'
  try {
    const value = JSON.parse(raw) as {
      headingPath?: string[]; startOffset?: number; endOffset?: number
      blocks?: { pageIndex: number; headingPath: string[] }[]
    }
    const heading = (value.headingPath?.length ? value.headingPath : value.blocks?.[0]?.headingPath)?.join(' / ')
    const pages = [...new Set(value.blocks?.map(block => block.pageIndex + 1))].join('、')
    const range = typeof value.startOffset === 'number' && typeof value.endOffset === 'number' ? `文本字符 ${value.startOffset}–${value.endOffset}` : ''
    return [pages && `第 ${pages} 页`, heading, range].filter(Boolean).join(' · ') || '资料片段'
  } catch { return '资料位置无法读取' }
}

export function SourceLink({ chunkId, label = '查看资料来源' }: { chunkId: string | null; label?: string }) {
  const show = useContext(SourceContext)
  if (!chunkId) return <small>未提供资料来源</small>
  return show ? <button className="text-button source-link" type="button" onClick={() => show(chunkId)}>{label}</button> : <small>来源 #{chunkId}</small>
}

export function SourceProvider({ knowledgeBaseId, children }: { knowledgeBaseId: string; children: ReactNode }) {
  const [chunkId, setChunkId] = useState<string | null>(null)
  const [source, setSource] = useState<Source | null>(null)
  const [error, setError] = useState('')
  const dialog = useRef<HTMLDialogElement>(null)
  const request = useRef(0)
  useEffect(() => {
    if (!chunkId) return
    const version = ++request.current
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    setSource(null); setError('')
    if (!dialog.current?.open) dialog.current?.showModal()
    apiRequest<Source>(`/api/knowledge-bases/${knowledgeBaseId}/source?chunkId=${encodeURIComponent(chunkId)}`)
      .then(value => { if (request.current === version) setSource(value) })
      .catch(e => { if (request.current === version) setError(e instanceof Error ? e.message : String(e)) })
    return () => { request.current++; document.body.style.overflow = previousOverflow }
  }, [chunkId, knowledgeBaseId])
  return <SourceContext.Provider value={setChunkId}>{children}
    <dialog className="source-drawer" ref={dialog} aria-label="资料来源" onClose={() => { setChunkId(null); request.current++ }}>
      <div className="drawer-header"><h2>资料来源</h2><button className="secondary" type="button" onClick={() => dialog.current?.close()}>关闭来源</button></div>
      {error ? <Feedback error>{error}</Feedback> : source ? <><h3>{source.documentTitle}</h3><p className="muted">{locationLabel(source.sourceLocation)}</p><pre className="source-text">{source.content.replace(/\n{3,}/g, '\n\n')}</pre>
        <details><summary>引用编号</summary><small>{source.chunkId}</small></details></> : <Feedback>正在读取已保存的资料…</Feedback>}
    </dialog>
  </SourceContext.Provider>
}
