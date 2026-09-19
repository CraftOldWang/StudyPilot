import { type ChangeEvent, useEffect, useRef, useState } from 'react'
import { DEMO_MODE } from '../deployment'
import type { KnowledgeBase } from '../types'
import { hashFile } from '../upload/hashFile'
import { cancelUpload, completeUpload, initializeUpload, storedBytes, uploadMissing, uploadStatus,
  validateUploadFile, type UploadTask } from '../upload/uploadClient'

const STORAGE_KEY = 'studypilot:upload:user1:v1'
type Phase = 'idle' | 'checking' | 'hashing' | 'initializing' | 'uploading' | 'pausing' | 'paused' | 'completing' | 'cancelling' | 'done' | 'error'
const phaseNames: Record<Phase, string> = { idle: '选择课程资料', checking: '正在同步上传进度', hashing: '正在校验本地文件', initializing: '正在准备上传',
  uploading: '正在保存分片', pausing: '正在暂停', paused: '上传已暂停', completing: '正在合并并校验完整文件',
  cancelling: '正在取消上传', done: '上传已完成', error: '上传尚未完成' }
const serverNames: Record<string, string> = { MERGING: '正在合并文件', VERIFYING: '正在校验完整文件',
  VERIFIED: '正在保存文档信息', INITIALIZING: '正在准备上传', UPLOADING: '等待继续上传', HASH_FAILED: '文件校验失败' }

function restore(): { task: UploadTask | null; note: string } {
  try {
    const text = localStorage.getItem(STORAGE_KEY)
    if (!text) return { task: null, note: '' }
    const task = JSON.parse(text) as UploadTask
    if (typeof task.knowledgeBaseId !== 'string' || typeof task.filename !== 'string' || !(task.size > 0)) {
      throw new Error('invalid saved upload')
    }
    return { task, note: '' }
  } catch {
    return { task: null, note: '无法读取本地上传记录；重新选择文件后，服务器会按文件内容查找未完成上传。' }
  }
}

function bytes(value: number) { return `${(value / 1024 / 1024).toFixed(1)} MiB` }

export function UploadWidget({ knowledgeBase, onUploaded }: { knowledgeBase: KnowledgeBase; onUploaded: (kbId: string) => void }) {
  const [saved] = useState(restore)
  const [task, setTask] = useState<UploadTask | null>(saved.task)
  const [phase, setPhase] = useState<Phase>(saved.task ? 'paused' : 'idle')
  const [progress, setProgress] = useState(0)
  const [error, setError] = useState('')
  const [note, setNote] = useState(saved.note)
  const [serverPhase, setServerPhase] = useState('')
  const taskRef = useRef(task)
  const fileRef = useRef<File | null>(null)
  const hashCache = useRef<{ file: File; hash: string } | null>(null)
  const input = useRef<HTMLInputElement>(null)
  const operation = useRef<AbortController | null>(null)
  const busy = useRef(false)
  const alive = useRef(true)
  const uploaded = useRef(onUploaded)
  uploaded.current = onUploaded

  useEffect(() => {
    alive.current = true
    let mounted = true
    const restored = taskRef.current
    if (restored?.sessionId) {
      setPhase('checking')
      void uploadStatus(restored.sessionId).then((status) => {
        if (!mounted) return
        setProgress(storedBytes(status))
        setServerPhase(status.status)
        if (status.status === 'COMPLETED') finished(restored)
        else if (status.status === 'CANCELLED') { remember(null); setPhase('idle') }
        else { setPhase('paused'); setError(status.errorMessage || '') }
      }).catch((caught) => {
        if (!mounted) return
        setPhase('error')
        setError(caught instanceof Error ? caught.message : '无法恢复上传进度。')
      })
    }
    return () => { mounted = false; alive.current = false; operation.current?.abort() }
  }, [])

  function remember(next: UploadTask | null) {
    taskRef.current = next
    if (alive.current) setTask(next)
    try {
      if (next) localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
      else localStorage.removeItem(STORAGE_KEY)
    } catch {
      if (alive.current) setNote('浏览器无法保存上传记录。当前页面仍可继续；刷新后请重新选择同一资料库和文件。')
    }
  }

  function finished(current: UploadTask) {
    if (!alive.current) return
    setPhase('done')
    setProgress(current.size)
    setError('')
    setServerPhase('')
    try { localStorage.removeItem(STORAGE_KEY) } catch { setNote('无法清除本地上传记录，服务器已确认上传完成。') }
    uploaded.current(current.knowledgeBaseId)
  }

  async function finishOnServer(current: UploadTask) {
    setPhase('completing')
    setServerPhase('MERGING')
    let reading = false
    let polling = true
    const timer = window.setInterval(async () => {
      if (reading || !alive.current) return
      reading = true
      try {
        const status = await uploadStatus(current.sessionId!)
        if (polling && alive.current) setServerPhase(status.status)
      } catch {
        if (polling && alive.current) setNote('暂时无法读取处理进度，仍在等待服务器确认完成。')
      } finally { reading = false }
    }, 1500)
    try {
      await completeUpload(current)
      finished(current)
    } finally { polling = false; window.clearInterval(timer) }
  }

  async function run(file: File) {
    if (busy.current) return
    const validation = validateUploadFile(file)
    if (validation) { setError(validation); return }
    const previous = phase === 'done' ? null : taskRef.current
    if (previous && (previous.filename !== file.name || previous.size !== file.size)) {
      setError('请选择原文件继续上传，或先取消当前上传任务。')
      return
    }
    busy.current = true
    const controller = new AbortController()
    operation.current = controller
    fileRef.current = file
    let current: UploadTask = previous ?? { knowledgeBaseId: knowledgeBase.id, knowledgeBaseName: knowledgeBase.name,
      filename: file.name, contentType: file.type || 'application/octet-stream', size: file.size }
    remember(current)
    setError('')
    setNote('')
    setPhase('hashing')
    setProgress(0)
    let transferStarted = false
    try {
      const hash = hashCache.current?.file === file ? hashCache.current.hash
        : await hashFile(file, controller.signal, (value) => alive.current && setProgress(value))
      hashCache.current = { file, hash }
      if (current.sha256 && current.sha256 !== hash) throw new Error('所选文件内容与原上传任务不同，请选择原文件。')
      current = { ...current, sha256: hash }
      remember(current)
      setPhase('initializing')
      const initialized = await initializeUpload(current)
      if (initialized.duplicated) { finished(current); return }
      if (!initialized.uploadSessionId) throw new Error('服务器未返回上传编号。')
      current = { ...current, sessionId: initialized.uploadSessionId }
      remember(current)
      if (!alive.current) return
      const status = await uploadStatus(current.sessionId!)
      if (status.status === 'COMPLETED') { finished(current); return }
      setProgress(storedBytes(status))
      transferStarted = true
      if (status.status === 'UPLOADING') {
        setPhase('uploading')
        await uploadMissing(file, status, controller.signal, (value) => alive.current && setProgress(value))
      }
      if (controller.signal.aborted || !alive.current) return
      await finishOnServer(current)
    } catch (caught) {
      if (alive.current) {
        setPhase(controller.signal.aborted ? 'paused' : 'error')
        if (!controller.signal.aborted) setError(caught instanceof Error ? caught.message : '上传失败，请重试。')
      }
    } finally {
      busy.current = false
      operation.current = null
      if (alive.current && controller.signal.aborted) {
        setPhase('paused')
        if (!transferStarted) setProgress(0)
      }
    }
  }

  async function choose(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (file) await run(file)
  }

  function pause() {
    setPhase('pausing')
    operation.current?.abort()
  }

  async function recover() {
    const current = taskRef.current
    if (busy.current || !current?.sessionId) return
    busy.current = true
    setError('')
    setPhase('checking')
    try {
      const status = await uploadStatus(current.sessionId)
      setProgress(storedBytes(status))
      setServerPhase(status.status)
      if (status.status === 'COMPLETED') finished(current)
      else if (['MERGING', 'VERIFYING', 'VERIFIED'].includes(status.status)) await finishOnServer(current)
      else {
        setPhase('paused')
        if (status.errorMessage) setError(status.errorMessage)
      }
    } catch (caught) {
      setPhase('error')
      setError(caught instanceof Error ? caught.message : '无法查询上传状态。')
    } finally { busy.current = false }
  }

  async function cancel() {
    let current = taskRef.current
    if (busy.current || !current) return
    busy.current = true
    setPhase('cancelling')
    setError('')
    try {
      // Resolve an uncertain init response before cancelling any remote upload.
      if (!current.sessionId && current.sha256) {
        const initialized = await initializeUpload(current)
        if (initialized.duplicated) { finished(current); return }
        if (!initialized.uploadSessionId) throw new Error('服务器未返回上传编号，取消尚未确认。')
        current = { ...current, sessionId: initialized.uploadSessionId }
        remember(current)
      }
      if (current.sessionId) await cancelUpload(current.sessionId)
      remember(null)
      fileRef.current = null
      hashCache.current = null
      setPhase('idle')
      setProgress(0)
    } catch (caught) {
      setPhase('error')
      setError(caught instanceof Error ? caught.message : '取消尚未确认，请重试或查询结果。')
    } finally { busy.current = false }
  }

  const running = ['checking', 'hashing', 'initializing', 'uploading', 'pausing', 'completing', 'cancelling'].includes(phase)
  const resumable = task && ['paused', 'error'].includes(phase)
  const label = phase === 'completing' ? serverNames[serverPhase] || phaseNames.completing : phaseNames[phase]
  return <div className="upload-widget">
    <div className="upload-heading">
      <div><strong>添加课程资料</strong><p>课件：PDF、PPTX、TXT、Markdown<br />{DEMO_MODE ? '共享演示 · 最大 50 MiB，请勿上传私人资料' : '音视频：MP4、M4A、MP3、WAV · 最大 1 GiB'}</p></div>
      <button type="button" disabled={running} onClick={() => input.current?.click()}>
        {resumable ? '重新选择同一文件' : '选择文件'}
      </button>
      <input ref={input} className="visually-hidden" type="file" disabled={running} accept={DEMO_MODE ? '.pdf,.pptx,.txt,.md,.markdown' : '.pdf,.pptx,.txt,.md,.markdown,.mp4,.m4a,.mp3,.wav'} onChange={choose} aria-label="选择课程资料" />
    </div>
    {task && <div className="upload-progress-card">
      <strong className="upload-filename">{task.filename}</strong>
      <p>上传至 {task.knowledgeBaseName}{task.knowledgeBaseId !== knowledgeBase.id && '（当前已切换到其他资料库）'}</p>
      <div className="upload-progress-label"><span role="status">{label}</span><span>{bytes(progress)} / {bytes(task.size)}</span></div>
      <progress aria-label={phase === 'hashing' ? '本地文件校验进度' : '已保存分片进度'} max={task.size} value={Math.min(progress, task.size)} />
      {phase === 'done' && <p>资料已保存，解析完成后即可检索。</p>}
      {phase === 'completing' && <p>服务器正在处理。离开页面后，可以回来查询完成结果。</p>}
      {resumable && <p>继续时会核对服务器进度，只补传缺失分片。</p>}
      <div className="upload-actions">
        {['hashing', 'uploading'].includes(phase) && <button className="secondary" type="button" onClick={pause}>暂停上传</button>}
        {resumable && fileRef.current && <button type="button" onClick={() => void run(fileRef.current!)}>继续上传</button>}
        {resumable && task.sessionId && <button className="secondary" type="button" onClick={() => void recover()}>查询或继续处理</button>}
        {task && !running && phase !== 'done' && <button className="secondary" type="button" onClick={() => void cancel()}>取消上传</button>}
      </div>
    </div>}
    {error && <p className="inline-error" role="alert">{error}</p>}
    {note && <p className="upload-note" role="status">{note}</p>}
  </div>
}
