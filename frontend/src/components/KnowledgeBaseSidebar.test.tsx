import { fireEvent, render, screen } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { KnowledgeBaseSidebar } from './KnowledgeBaseSidebar'
vi.mock('../learningApi', () => ({ learningApi: { listSessions: vi.fn().mockResolvedValue([
  { id: 'chat-1', learningGoal: '进程和线程的区别' },
]) } }))

it('opens a listed conversation in its owning knowledge base', async () => {
  const onSession = vi.fn()
  render(<KnowledgeBaseSidebar items={[{ id: 'os', name: '操作系统', createdAt: '', updatedAt: '' }]} selectedId="os"
    activeSessionId={null} loading={false} view="new" revision={0} onNavigate={vi.fn()} onSelect={vi.fn()} onSession={onSession} />)
  fireEvent.click(await screen.findByRole('button', { name: '进程和线程的区别' }))
  expect(onSession).toHaveBeenCalledWith('os', 'chat-1')
})
