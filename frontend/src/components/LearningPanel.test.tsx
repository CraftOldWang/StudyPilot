import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ConversationTurn, LearningSession } from '../learningTypes'

const mock = vi.hoisted(() => ({ getSession: vi.fn(), history: vi.fn(), streamMessage: vi.fn() }))
vi.mock('../learningApi', () => ({ learningApi: mock }))
vi.mock('../api', () => ({ api: { listDocuments: vi.fn().mockResolvedValue([]) }, apiRequest: vi.fn().mockResolvedValue([]) }))
import { LearningPanel } from './LearningPanel'

const point = { id: '901', sequenceNo: 1, topic: '可见性', subtopics: [], estimatedMinutes: 20,
  status: 'EXPLAINING' as const, explanation: '已保存讲解', errorMessage: null }
const session: LearningSession = { id: '9007199254740999', learningGoal: '理解内存模型', knowledgeBaseId: '20', status: 'ACTIVE',
  activeKnowledgePoint: point, plan: [point], cards: [], currentQuiz: null, errorMessage: null }
const turn: ConversationTurn = { id: '9007199254741001', requestId: 'saved-request', userMessage: '先前问题', assistantMessage: '先前回答',
  status: 'SUCCEEDED', phase: 'COMPLETE', errorMessage: null, artifactJson: null, traceId: 'trace', createdAt: '' }
async function restore() {
  await screen.findByRole('heading', { name: turn.userMessage })
}
describe('durable learning conversation', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    HTMLElement.prototype.scrollIntoView = vi.fn()
    mock.getSession.mockResolvedValue(session)
    mock.history.mockResolvedValue([turn])
  })
  it('restores full saved history and retries uncertain delivery using exactly the same request', async () => {
    mock.streamMessage.mockRejectedValueOnce(new Error('连接断开'))
    mock.streamMessage.mockImplementationOnce(async (_id, message, requestId, onEvent) => {
      onEvent({ event: 'result', data: { session, answer: '新的回答', turn: { ...turn, id: '9007199254741002', requestId, userMessage: message, assistantMessage: '新的回答' } } })
    })
    render(<LearningPanel initialSessionId={session.id} onBack={vi.fn()} onOutline={vi.fn()} knowledgeBase={{ id: '20', name: '课程', createdAt: '', updatedAt: '' }} onSessionKnowledgeBase={vi.fn()} />)
    await restore()
    expect(screen.getAllByText('先前问题').length).toBeGreaterThan(0)
    expect(screen.getByText('先前回答')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('继续学习或提问'), { target: { value: '再举个例子' } })
    fireEvent.keyDown(screen.getByLabelText('继续学习或提问'), { key: 'Enter', ctrlKey: true, isComposing: true })
    expect(mock.streamMessage).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '发送消息' }))
    expect(await screen.findByText('连接断开')).toBeInTheDocument()
    expect(screen.getByLabelText('继续学习或提问')).toHaveValue('再举个例子')
    expect(screen.getByRole('button', { name: '发送消息' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '重试原回合' }))
    expect(await screen.findByText('新的回答')).toBeInTheDocument()
    expect(mock.streamMessage.mock.calls[0].slice(0, 3)).toEqual(mock.streamMessage.mock.calls[1].slice(0, 3))
    expect(screen.getByLabelText('继续学习或提问')).toHaveValue('')
    expect(screen.getByText('先前回答')).toBeInTheDocument()
  })
  it('does not show a failed history read as an empty restored conversation', async () => {
    mock.history.mockRejectedValueOnce(new Error('聊天记录读取失败'))
    render(<LearningPanel initialSessionId={session.id} onBack={vi.fn()} onOutline={vi.fn()} knowledgeBase={{ id: '20', name: '课程', createdAt: '', updatedAt: '' }} onSessionKnowledgeBase={vi.fn()} />)
        expect(await screen.findByText('聊天记录读取失败')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: session.learningGoal })).not.toBeInTheDocument()
  })
  it('keeps the session scope when the sidebar selects another knowledge base', async () => {
    const select = vi.fn()
    const { rerender } = render(<LearningPanel initialSessionId={session.id} onBack={vi.fn()} onOutline={vi.fn()} knowledgeBase={{ id: '20', name: '课程', createdAt: '', updatedAt: '' }} onSessionKnowledgeBase={select} />)
    await restore()
    rerender(<LearningPanel initialSessionId={session.id} onBack={vi.fn()} onOutline={vi.fn()} knowledgeBase={{ id: '99', name: '另一个资料库', createdAt: '', updatedAt: '' }} onSessionKnowledgeBase={select} />)
    fireEvent.click(screen.getByRole('button', { name: '切回会话资料库' }))
    await waitFor(() => expect(select).toHaveBeenLastCalledWith('20'))
    expect(screen.getByText('先前回答')).toBeInTheDocument()
  })
})
