import { isDocumentTerminal, statusLabel } from '../status'
import type { DocumentItem, KnowledgeBase } from '../types'
import { UploadWidget } from './UploadWidget'
import { useState } from 'react'
import { apiRequest } from '../api'

function displayTime(value: string) {
  const utc = /(?:Z|[+-]\d{2}:\d{2})$/.test(value) ? value : `${value}Z`
  return new Date(utc).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' })
}

interface Props {
  knowledgeBase: KnowledgeBase
  documents: DocumentItem[]
  loading: boolean
  onUploaded: (knowledgeBaseId: string) => void
}

export function DocumentPanel({ knowledgeBase, documents, loading, onUploaded }: Props) {
  const [filter, setFilter] = useState('')
  const [retrying, setRetrying] = useState<string | null>(null)
  const [error, setError] = useState('')
  const visible = documents.filter(d => d.title.toLocaleLowerCase().includes(filter.toLocaleLowerCase()))
  async function retry(id: string) {
    setRetrying(id); setError('')
    try { await apiRequest(`/api/documents/${id}/retry`, { method: 'POST' }); onUploaded(knowledgeBase.id) }
    catch (e) { setError(e instanceof Error ? e.message : String(e)) }
    finally { setRetrying(null) }
  }
  return (
    <section className="panel documents-panel">
      <div className="panel-header">
        <div>
          <h1>{knowledgeBase.name}</h1>
          <p>{documents.length} 份资料</p>
        </div>
      </div>
      <UploadWidget knowledgeBase={knowledgeBase} onUploaded={onUploaded} />
      <input className="file-filter" aria-label="搜索文件" placeholder="搜索文件名…" value={filter} onChange={e => setFilter(e.target.value)} />
      {error && <div className="feedback feedback-error" role="alert">{error}</div>}

      {loading ? (
        <div className="empty-state">正在读取文档状态…</div>
      ) : documents.length === 0 ? (
        <div className="empty-state">
          <span className="empty-icon">资料</span>
          <strong>还没有资料</strong>
          <p>从一份课件开始。资料处理完成后，会显示为“可检索”。</p>
        </div>
      ) : (
        <div className="document-table-wrap">
          <table>
            <thead>
              <tr><th>文档</th><th>状态</th><th>更新时间</th><th><span className="visually-hidden">操作</span></th></tr>
            </thead>
            <tbody>
              {visible.map((document) => (
                <tr key={document.id}>
                  <td>
                    <strong>{document.title}</strong>
                    {document.errorMessage && <span className="document-error">{document.errorMessage}</span>}
                  </td>
                  <td>
                    <span className={`status status-${document.pipelineStatus.toLowerCase()}`}>
                      {!isDocumentTerminal(document.pipelineStatus) && <span className="pulse" />}
                      {statusLabel(document.pipelineStatus)}
                    </span>
                  </td>
                  <td>{displayTime(document.updatedAt)}</td>
                  <td>{document.pipelineStatus === 'FAILED' && <button className="plain" disabled={retrying !== null} onClick={() => void retry(document.id)}>{retrying === document.id ? '重试中…' : '重试处理'}</button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {!visible.length && <p className="feedback">没有匹配的文件。</p>}
        </div>
      )}
    </section>
  )
}
