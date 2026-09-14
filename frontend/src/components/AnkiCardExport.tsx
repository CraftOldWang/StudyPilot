import { useEffect, useRef, useState } from 'react'
import { apiRequest } from '../api'
import { Feedback } from './ui/Feedback'

export interface AnkiExportStatus {
  cardId: string
  status: 'PENDING' | 'EXPORTING' | 'SUCCEEDED' | 'FAILED'
  noteId?: string
  errorMessage?: string
  attempts: number
}

export function AnkiCardExport({ cardId }: { cardId: string }) {
  const [status, setStatus] = useState<AnkiExportStatus | null>(null)
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const alive = useRef(false)
  const submitting = useRef(false)
  const path = `/api/review/cards/${cardId}/anki`

  useEffect(() => {
    alive.current = true
    let current = true
    setLoading(true)
    void apiRequest<AnkiExportStatus>(path).then(value => {
      if (current) { setStatus(value); setError(value.errorMessage || '') }
    }).catch((e: unknown) => {
      if (current) setError(e instanceof Error ? e.message : '导出状态读取失败，请重试。')
    }).finally(() => { if (current) setLoading(false) })
    return () => { current = false; alive.current = false }
  }, [path])

  async function exportCard() {
    if (submitting.current) return
    submitting.current = true
    setBusy(true); setError('')
    try {
      const result = await apiRequest<AnkiExportStatus>(path, { method: 'POST' })
      if (alive.current) setStatus(result)
    } catch (e) {
      if (alive.current) setError(e instanceof Error ? e.message : '导出失败，可重试确认结果。')
    } finally {
      submitting.current = false
      if (alive.current) setBusy(false)
    }
  }

  return <div className="anki-export" aria-busy={busy || loading}>
    {loading ? <small className="muted">正在读取导出状态…</small> : <>
      {status?.status === 'SUCCEEDED' && !error && <small role="status">已导出至 Anki</small>}
      {status?.status === 'EXPORTING' && !busy && <small className="muted">上次导出结果待确认，重试会先查找已有卡片。</small>}
      {(status?.status !== 'SUCCEEDED' || error) && <button className="secondary" type="button" disabled={busy} onClick={() => void exportCard()}>
        {busy ? '正在导出…' : error || status?.status === 'EXPORTING' ? '重试导出到 Anki' : status?.status === 'SUCCEEDED' ? '再次导出到 Anki' : '导出到 Anki'}
      </button>}
    </>}
    {error && <Feedback error>{error}</Feedback>}
  </div>
}
