import { type FormEvent, useEffect, useRef, useState } from 'react'
import { learningApi } from '../learningApi'
import type { ConversationTurn, LearningSession, LearningTurn } from '../learningTypes'
import type { KnowledgeBase } from '../types'
import { QuizSection } from './QuizSection'
import { ReviewCards } from './ReviewCards'
import { CardDrafts } from './CardDrafts'
import { ToolCalls, type ToolCall } from './ToolCalls'
import { apiRequest } from '../api'
import { Feedback } from './ui/Feedback'
import { Field, MultilineInput } from './ui/Field'
import { SavedArtifacts } from './SavedArtifacts'
import { MessageContent } from './ui/MessageContent'
import { SourceProvider } from './SourceDrawer'

interface Props { knowledgeBase: KnowledgeBase; initialSessionId: string; initialMessage?: string; onChanged?: () => void; onSessionKnowledgeBase: (id: string) => void; onBack: () => void; onOutline: () => void }
interface Pending { requestId: string; message: string }
export function LearningPanel({ knowledgeBase, initialSessionId, initialMessage, onChanged, onSessionKnowledgeBase, onBack, onOutline }: Props) {
  const [session, setSession] = useState<LearningSession | null>(null)
  const [history, setHistory] = useState<ConversationTurn[]>([])
  const [message, setMessage] = useState('')
  const [pending, setPending] = useState<Pending | null>(null)
  const [partial, setPartial] = useState('')
  const [progress, setProgress] = useState('')
  const [tools, setTools] = useState<ToolCall[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const lock = useRef(false)
  const mounted = useRef(true)
  const transport = useRef<AbortController>()
  const sessionId = useRef('')
  const chatScroll = useRef<HTMLDivElement>(null)
  const [showLatest, setShowLatest] = useState(false)
  const follow = useRef(true)
  const draft = useRef(new Map<string, string>())
  const pendingBySession = useRef(new Map<string, Pending>())
  const firstMessageSent = useRef(false)
  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false; transport.current?.abort() }
  }, [])
  useEffect(() => {
    const viewport = chatScroll.current
    if (!viewport) return
    const scroll = () => { if (follow.current) viewport.scrollTop = viewport.scrollHeight }
    scroll()
    // Saved tool blocks arrive after the conversation, so follow their content updates too.
    const observer = new MutationObserver(scroll)
    observer.observe(viewport, { childList: true, subtree: true, characterData: true })
    return () => observer.disconnect()
  }, [session?.id])

  function jumpToLatest() {
    follow.current = true; setShowLatest(false)
    if (chatScroll.current) chatScroll.current.scrollTop = chatScroll.current.scrollHeight
  }

  function clearSentDraft(id: string, sent: string) {
    setMessage(current => current.trim() === sent ? '' : current)
    if (draft.current.get(id)?.trim() === sent) draft.current.delete(id)
  }

  async function loadSession(value: LearningSession) {
    const turns = await learningApi.history(value.id)
    if (!mounted.current) return
    if (sessionId.current !== value.id) { follow.current = true; setShowLatest(false) }
    sessionId.current = value.id
    setMessage(draft.current.get(value.id) || '')
    const savedPending = pendingBySession.current.get(value.id)
    const alreadySaved = turns.some(t => t.requestId === savedPending?.requestId && t.status === 'SUCCEEDED')
    if (alreadySaved) pendingBySession.current.delete(value.id)
    setSession(value); setHistory(turns); setPartial(''); setPending(alreadySaved ? null : savedPending || null)
    onSessionKnowledgeBase(value.knowledgeBaseId)
  }
  useEffect(() => { void openSession() }, [initialSessionId])
  async function openSession() {
    if (lock.current) return
    lock.current = true; setBusy(true); setError('')
    try { await loadSession(await learningApi.getSession(initialSessionId)) }
    catch (e) { if (mounted.current) setError(e instanceof Error ? e.message : String(e)) }
    finally { lock.current = false; if (mounted.current) setBusy(false) }
  }
  async function refresh() {
    if (!session || lock.current) return
    lock.current = true; setBusy(true); setError('')
    try {
      const [value, turns] = await Promise.all([learningApi.getSession(session.id), learningApi.history(session.id)])
      if (!mounted.current || sessionId.current !== value.id) return
      setSession(value); setHistory(turns)
      if (pending && turns.some(t => t.requestId === pending.requestId && t.status === 'SUCCEEDED')) { setPending(null); pendingBySession.current.delete(session.id); setPartial(''); clearSentDraft(session.id, pending.message) }
    } catch (e) { setError(e instanceof Error ? e.message : String(e)) }
    finally { lock.current = false; if (mounted.current) setBusy(false) }
  }
  async function send(text: string, retry?: Pending) {
    if (!session || lock.current || !text.trim() || (!retry && !canSend)) return
    setTools([])
    const request = retry || { message: text.trim(), requestId: crypto.randomUUID() }
    const id = session.id
    follow.current = true; setShowLatest(false)
    pendingBySession.current.set(id, request)
    lock.current = true; setBusy(true); setError(''); setPartial(''); setProgress('正在连接…'); setPending(request)
    transport.current = new AbortController()
    try {
      await learningApi.streamMessage(id, request.message, request.requestId, event => {
        if (!mounted.current || sessionId.current !== id) return
        if (event.event === 'text') setPartial(p => p + (event.data as { text: string }).text)
        else if (event.event === 'progress') setProgress((event.data as { text: string }).text)
        else if (event.event === 'accepted') setProgress('请求已收到，正在处理…')
        else if (event.event === 'failure') throw new Error((event.data as { message: string }).message)
        else if (event.event === 'tool') {
          const call = JSON.parse((event.data as { text: string }).text) as ToolCall
          setTools(current => [...current.filter(c => c.id !== call.id), call])
        }
        else if (event.event === 'result') {
          const result = event.data as LearningTurn
          setSession(result.session)
          if (!result.turn) throw new Error('响应缺少回合状态，请查询已保存结果。')
          const turn = result.turn
          setHistory(current => [...current.filter(t => t.requestId !== turn.requestId), turn])
          setPartial('')
          if (turn.status === 'SUCCEEDED') { setPending(null); pendingBySession.current.delete(id); clearSentDraft(id, request.message); setProgress('已保存') }
          else if (turn.status === 'FAILED') {
            setError(turn.errorMessage || '本次未完成，可重试原回合。')
            if (turn.phase === 'MODEL') { setPending(null); pendingBySession.current.delete(id) }
          }
          else setProgress('后台仍在处理，请稍后查询结果。')
        }
      }, transport.current.signal)
    } catch (e) {
      if (mounted.current) setError(e instanceof Error && e.name === 'AbortError' ? '已断开显示，后台处理不会取消。请查询结果。' : e instanceof Error ? e.message : String(e))
    } finally { transport.current = undefined; lock.current = false; if (mounted.current) { setBusy(false); onChanged?.() } }
  }
  const active = session?.activeKnowledgePoint
  const lastTurn = history.at(-1)
  const unresolved = lastTurn?.status !== 'SUCCEEDED' ? lastTurn : undefined
  const retry = pending || (unresolved ? { requestId: unresolved.requestId, message: unresolved.userMessage } : null)
  const blocking = pending || (unresolved && (unresolved.status === 'RUNNING' || unresolved.phase === 'ARTIFACTS_COMMITTED'))
  const canSend = !!active && active.status !== 'CARD_CONFIRMING' && !busy && !blocking
  const hint = active?.status === 'NEW' ? '开始这个知识点的讲解' : active?.status === 'EXPLAINING' ? '我理解了，继续练习' : active?.status === 'FEEDBACK' ? '没有疑问了，继续生成复习卡片' : ''
  useEffect(() => {
    if (initialMessage && session && canSend && !firstMessageSent.current) {
      firstMessageSent.current = true
      if (!history.length) void send(initialMessage)
    }
  }, [session?.id, canSend, initialMessage])
  async function saveCards(cards: LearningSession['cards'], confirm = false) {
    if (!session || !active || lock.current) return
    lock.current = true; setBusy(true); setError(''); setProgress(confirm ? '正在确认卡片、写入 Anki 并整理学习记录…' : '正在保存卡片…')
    try {
      if (active.status !== 'CARD_CONFIRMING') {
        await apiRequest<LearningSession>(`/api/learning/sessions/${session.id}/points/${active.id}/cards`, {
          method: 'PUT', body: JSON.stringify(cards.map(({ id, front, back }) => ({ id, front, back }))),
        })
      }
      if (confirm) await apiRequest<LearningSession>(`/api/learning/sessions/${session.id}/points/${active.id}/cards/confirm`, { method: 'POST' })
      await loadSession(await learningApi.getSession(session.id))
      onChanged?.()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      const current = await learningApi.getSession(session.id).catch(() => null)
      if (current) setSession(current)
    } finally { lock.current = false; setBusy(false) }
  }
  if (!session) return <section className="panel learning-start">
    <h1>打开学习会话</h1>
    {error ? <Feedback error>{error}<button type="button" disabled={busy} onClick={() => void openSession()}>重新读取会话</button></Feedback> : <Feedback>正在读取已保存的学习进度和对话…</Feedback>}
    <button className="secondary" type="button" onClick={onBack}>返回新对话</button>
  </section>
  return <SourceProvider knowledgeBaseId={session.knowledgeBaseId}><div className="learning-workspace">
    <section className="learning-summary"><div><span className="muted">{knowledgeBase.name}</span><h1>{history[0]?.userMessage.slice(0, 55) || '新对话'}</h1></div>
      <button className="plain" type="button" onClick={onOutline}>学习大纲 ↗</button></section>
    {session.knowledgeBaseId !== knowledgeBase.id && <Feedback>当前会话仍绑定原资料库。<button className="text-button" type="button" onClick={() => onSessionKnowledgeBase(session.knowledgeBaseId)}>切回会话资料库</button></Feedback>}
    {(error || session.errorMessage) && <Feedback error>{error || session.errorMessage}</Feedback>}
    <div className="learning-columns">
      <section className="learning-focus" aria-label="学习对话">
        <div className="focus-heading"><span className="muted">{active ? '正在学习' : '学习记录'}</span><span>{active?.topic || '本轮学习已完成'}</span></div>
        <div className="chat-scroll-wrapper">
        <div className="chat-scroll" ref={chatScroll} role="region" aria-label="对话记录" tabIndex={0} onScroll={event => {
          const node = event.currentTarget
          follow.current = node.scrollHeight - node.scrollTop - node.clientHeight < 100
          setShowLatest(!follow.current)
        }}>
        {history.length === 0 && !initialMessage && <Feedback>{session.status === 'COMPLETED' ? '大纲中的知识点已完成，可以从侧栏查看之前的学习记录。' : '发送一条消息开始学习，也可以先提问。'}</Feedback>}
        {history.length === 0 && active?.explanation && <article className="chat-answer"><MessageContent text={active.explanation} /></article>}
        <div className="conversation-history">{history.map(turn => <article className="conversation-turn" key={turn.id}>
          <div className="chat-user"><span>你</span><p>{turn.userMessage}</p></div>
          {turn.assistantMessage && <div className="chat-answer"><span>StudyPilot</span><MessageContent text={turn.assistantMessage} /></div>}
          <ToolCalls sessionId={session.id} turnId={turn.id} />
          <SavedArtifacts json={turn.artifactJson} currentQuizId={session.currentQuiz?.quizId} hideCards={turn.knowledgePointId === active?.id} />
          {turn.status !== 'SUCCEEDED' && <small className={turn.status === 'FAILED' ? 'field-error' : 'muted'}>{turn.status === 'FAILED' ? `未完成：${turn.errorMessage || '可重试原回合'}` : '处理中，可查询进度'}</small>}
        </article>)}</div>
        {pending && !history.some(t => t.requestId === pending.requestId) && <div className="chat-user"><span>你 · 待确认</span><p>{pending.message}</p></div>}
        {busy && <ToolCalls live={tools} />}
        {partial && <div className="chat-answer streaming"><span>StudyPilot · 生成中</span><MessageContent text={partial} /></div>}
        {busy && <Feedback>{progress || '正在处理…'} {transport.current && <button className="text-button" type="button" onClick={() => transport.current?.abort()}>断开显示</button>}</Feedback>}
        {!busy && retry && <div className="recovery-actions"><Feedback>{blocking ? '请先确认上次消息的结果。' : '上次生成未完成，也可以调整消息后继续。'}重试会沿用同一请求编号。</Feedback><button disabled={busy} type="button" onClick={() => void send(retry.message, retry)}>重试原回合</button></div>}
        {!busy && (retry || error) && <button className="secondary" type="button" onClick={() => void refresh()}>查询已保存结果</button>}
        {session.currentQuiz && <QuizSection busy={!canSend} quiz={session.currentQuiz} onSubmit={async answers => {
          const quiz = session.currentQuiz!
          await send(answers.map((answer, i) => `${i + 1}. ${'ABCD'[quiz.questions[i].options.indexOf(answer)]}`).join('\n'))
        }} />}
        {active && ['CARD_GENERATING', 'CARD_CONFIRMING'].includes(active.status)
          ? <CardDrafts cards={session.cards} busy={busy || !!blocking} confirming={active.status === 'CARD_CONFIRMING'}
              onSave={cards => saveCards(cards)} onConfirm={cards => saveCards(cards, true)}
              onRewrite={() => { setMessage('请重写这些卡片：'); document.getElementById('learning-message')?.focus() }} />
          : <ReviewCards cards={session.cards} />}
        </div>
        {showLatest && <button className="jump-latest secondary" type="button" onClick={jumpToLatest}>↓ 返回最新消息</button>}
        </div>
        {active && <form noValidate className="learning-message-form" onSubmit={e => { e.preventDefault(); void send(message) }}>
          <Field id="learning-message" label="继续学习或提问">
            <MultilineInput id="learning-message" rows={3} value={message} maxLength={12000} onChange={e => { setMessage(e.target.value); draft.current.set(session.id, e.target.value) }} onKeyDown={e => {
              if (e.ctrlKey && e.key === 'Enter' && !e.nativeEvent.isComposing && canSend) { e.preventDefault(); void send(message) }
            }} placeholder="例如：先用一个例子解释，再带我做题" /></Field>
          <div className="action-row"><button className="send-button" aria-label="发送消息" disabled={!canSend || !message.trim()} type="submit">{busy ? '…' : '↑'}</button>
            {hint && <button className="secondary" disabled={!canSend} onClick={() => void send(hint)} type="button">{active.status === 'NEW' ? '开始讲解' : active.status === 'EXPLAINING' ? '进入测验' : '生成复习卡'}</button>}</div>
        </form>}
      </section></div></div></SourceProvider>
}
