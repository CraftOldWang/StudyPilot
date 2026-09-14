import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { DocumentItem } from './types'

const apiMock = vi.hoisted(() => ({
  listKnowledgeBases: vi.fn(),
  listDocuments: vi.fn(),
  createKnowledgeBase: vi.fn(),
  renameKnowledgeBase: vi.fn(),
  search: vi.fn(),
  agentSearch: vi.fn(),
}))

vi.mock('./api', () => ({ api: apiMock, apiRequest: vi.fn(), ApiError: Error }))
vi.mock('./learningApi', () => ({ learningApi: { listSessions: vi.fn().mockResolvedValue([]), currentPlan: vi.fn().mockResolvedValue(null) } }))
const uploadMock = vi.hoisted(() => ({ initializeUpload: vi.fn(), uploadStatus: vi.fn(), uploadMissing: vi.fn(), completeUpload: vi.fn() }))
vi.mock('./upload/hashFile', () => ({ hashFile: vi.fn().mockResolvedValue('a'.repeat(64)) }))
vi.mock('./upload/uploadClient', async (original) => ({ ...await original<typeof import('./upload/uploadClient')>(), ...uploadMock }))

import App from './App'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

function document(id: string, knowledgeBaseId: string, title: string): DocumentItem {
  return {
    id,
    knowledgeBaseId,
    fileRecordId: id,
    title,
    contentType: 'application/pdf',
    pipelineStatus: 'INDEXED',
    errorMessage: null,
    createdAt: '2026-09-05T10:00:00',
    updatedAt: '2026-09-05T10:00:00',
  }
}

describe('App knowledge-base request association', () => {
  beforeEach(() => { vi.clearAllMocks(); localStorage.clear() })

  it('ignores an earlier knowledge base response and its finally after selection changes', async () => {
    const first = deferred<DocumentItem[]>()
    const second = deferred<DocumentItem[]>()
    apiMock.listKnowledgeBases.mockResolvedValue([
      { id: '9007199254740993', name: '知识库一', createdAt: '', updatedAt: '' },
      { id: '9007199254740995', name: '知识库二', createdAt: '', updatedAt: '' },
    ])
    apiMock.listDocuments.mockImplementation((id: string) =>
      id === '9007199254740993' ? first.promise : second.promise)

    render(<App />)
    await waitFor(() => expect(apiMock.listDocuments).toHaveBeenCalledWith('9007199254740993'))
    fireEvent.click(screen.getByRole('button', { name: '知识库二' }))
    fireEvent.click(screen.getByRole('button', { name: '资料库' }))
    await waitFor(() => expect(apiMock.listDocuments).toHaveBeenCalledWith('9007199254740995'))

    first.resolve([document('11', '9007199254740993', '旧知识库.pdf')])
    await waitFor(() => expect(screen.getByText('正在读取文档状态…')).toBeInTheDocument())
    expect(screen.queryByText('旧知识库.pdf')).not.toBeInTheDocument()

    second.resolve([document('22', '9007199254740995', '当前知识库.pdf')])
    expect(await screen.findByText('当前知识库.pdf')).toBeInTheDocument()
    expect(screen.queryByText('旧知识库.pdf')).not.toBeInTheDocument()
  })

  it('does not let a completed upload from the previous knowledge base invalidate the current load', async () => {
    const upload = deferred<{ fileId: string; documentId: string; status: string }>()
    const secondDocuments = deferred<DocumentItem[]>()
    apiMock.listKnowledgeBases.mockResolvedValue([
      { id: '101', name: '知识库 A', createdAt: '', updatedAt: '' },
      { id: '202', name: '知识库 B', createdAt: '', updatedAt: '' },
    ])
    apiMock.listDocuments.mockImplementation((id: string) =>
      id === '101' ? Promise.resolve([]) : secondDocuments.promise)
    uploadMock.initializeUpload.mockResolvedValue({ uploadSessionId: '55', duplicated: false })
    uploadMock.uploadStatus.mockResolvedValue({ status: 'UPLOADING', fileSize: 3, chunkSize: 3, uploadedChunkIndexes: [] })
    uploadMock.uploadMissing.mockResolvedValue(undefined)
    uploadMock.completeUpload.mockReturnValue(upload.promise)

    const { container } = render(<App />)
    await waitFor(() => expect(apiMock.listDocuments).toHaveBeenCalledWith('101'))
    fireEvent.click(screen.getByRole('button', { name: '资料库' }))
    const fileInput = container.querySelector('input[type="file"]') as HTMLInputElement
    fireEvent.change(fileInput, {
      target: { files: [new File(['pdf'], 'source.pdf', { type: 'application/pdf' })] },
    })
    await waitFor(() => expect(uploadMock.completeUpload).toHaveBeenCalledWith(expect.objectContaining({ knowledgeBaseId: '101' })))

    fireEvent.click(screen.getByRole('button', { name: '知识库 B' }))
    fireEvent.click(screen.getByRole('button', { name: '资料库' }))
    await waitFor(() => expect(apiMock.listDocuments).toHaveBeenCalledWith('202'))
    await act(async () => { upload.resolve({ fileId: '301', documentId: '401', status: 'RECEIVED' }); await upload.promise })
    await waitFor(() => expect(apiMock.listDocuments).toHaveBeenCalledTimes(2))
    expect(screen.getByText('正在读取文档状态…')).toBeInTheDocument()

    secondDocuments.resolve([document('501', '202', 'B 当前文档.pdf')])
    expect(await screen.findByText('B 当前文档.pdf')).toBeInTheDocument()
  })

  it('keeps knowledge base creation disabled until the initial list finishes', async () => {
    const initialList = deferred<never[]>()
    apiMock.listKnowledgeBases.mockReturnValue(initialList.promise)

    render(<App />)
    expect(screen.getByRole('button', { name: '创建知识库' })).toBeDisabled()

    initialList.resolve([])
    await waitFor(() => expect(screen.getByRole('button', { name: '创建知识库' })).toBeEnabled())
    expect(apiMock.createKnowledgeBase).not.toHaveBeenCalled()
  })
})
