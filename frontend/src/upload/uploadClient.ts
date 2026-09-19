import { apiRequest } from '../api'
import type { UploadResult } from '../types'
import { DEMO_MODE } from '../deployment'

export const UPLOAD_CHUNK_BYTES = 8 * 1024 * 1024
export const UPLOAD_CONCURRENCY = 4
export const MAX_UPLOAD_BYTES = (DEMO_MODE ? 50 : 1024) * 1024 * 1024

export interface UploadTask {
  knowledgeBaseId: string
  knowledgeBaseName: string
  filename: string
  contentType: string
  size: number
  sha256?: string
  sessionId?: string
}

export interface UploadStatus {
  uploadSessionId: string
  knowledgeBaseId: string
  filename: string
  fileHash: string
  fileSize: number
  chunkSize: number
  totalChunks: number
  uploadedChunkIndexes: number[]
  missingChunkIndexes: number[]
  status: string
  fileId?: string
  documentId?: string
  errorMessage?: string
}

export function validateUploadFile(file: File): string | null {
  if (DEMO_MODE && !/\.(pdf|pptx|txt|md|markdown)$/i.test(file.name)) return '在线演示仅支持 PDF、PPTX、TXT 和 Markdown，音视频请使用本地版。'
  if (!/\.(pdf|pptx|txt|md|markdown|mp4|m4a|mp3|wav)$/i.test(file.name)) return '支持 PDF、PPTX、TXT、Markdown、MP4、M4A、MP3 和 WAV 文件。'
  if (file.size === 0) return '文件为空，请重新选择。'
  if (file.size > MAX_UPLOAD_BYTES) return DEMO_MODE ? '文件超过演示站 50 MiB 上传上限。' : '文件超过 1 GiB 上传上限。'
  return null
}

export async function initializeUpload(task: UploadTask) {
  if (!task.sha256) throw new Error('文件校验尚未完成。')
  const body = new FormData()
  const fields = { knowledgeBaseId: task.knowledgeBaseId, filename: task.filename,
    contentType: task.contentType, sha256: task.sha256, fileSize: task.size,
    chunkSize: UPLOAD_CHUNK_BYTES, totalChunks: Math.ceil(task.size / UPLOAD_CHUNK_BYTES) }
  for (const [key, value] of Object.entries(fields)) body.append(key, String(value))
  return apiRequest<{ uploadSessionId?: string; duplicated: boolean; documentId?: string }>('/api/files/multipart/init', {
    method: 'POST', body, signal: AbortSignal.timeout(120_000),
  })
}

export async function uploadStatus(sessionId: string): Promise<UploadStatus> {
  // The backend serializes Java Long values as strings, including file sizes.
  const raw = await apiRequest<Omit<UploadStatus, 'fileSize'> & { fileSize: string | number }>(
    `/api/files/multipart/${sessionId}`, { signal: AbortSignal.timeout(30_000) })
  const fileSize = Number(raw.fileSize)
  if (!Number.isSafeInteger(fileSize) || fileSize < 1) throw new Error('服务器返回的文件大小无效。')
  return { ...raw, fileSize }
}

export async function completeUpload(task: UploadTask): Promise<UploadResult> {
  const body = new FormData()
  body.append('uploadSessionId', task.sessionId!)
  body.append('knowledgeBaseId', task.knowledgeBaseId)
  return apiRequest<UploadResult>('/api/files/multipart/complete', { method: 'POST', body, signal: AbortSignal.timeout(600_000) })
}

export const cancelUpload = (sessionId: string) =>
  apiRequest<void>(`/api/files/multipart/${sessionId}/cancel`, { method: 'POST', signal: AbortSignal.timeout(120_000) })

export function storedBytes(status: UploadStatus): number {
  return status.uploadedChunkIndexes.reduce((total, index) => total +
    Math.min(status.chunkSize, status.fileSize - index * status.chunkSize), 0)
}

export async function uploadMissing(file: File, status: UploadStatus, signal: AbortSignal, onSaved: (bytes: number) => void) {
  let next = 0
  let saved = storedBytes(status)
  let failed: unknown
  onSaved(saved)
  const worker = async () => {
    while (!failed && !signal.aborted && next < status.missingChunkIndexes.length) {
      const index = status.missingChunkIndexes[next++]
      const chunk = file.slice(index * status.chunkSize, (index + 1) * status.chunkSize)
      const body = new FormData()
      body.append('chunk', chunk, `${index}.part`)
      try {
        await apiRequest<void>(`/api/files/multipart/${status.uploadSessionId}/chunks/${index}`, { method: 'POST', body, signal })
        saved += chunk.size
        onSaved(saved)
      } catch (error) {
        failed = error
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(UPLOAD_CONCURRENCY, status.missingChunkIndexes.length) }, worker))
  if (signal.aborted) throw new DOMException('已暂停上传。', 'AbortError')
  if (failed) throw failed
}
