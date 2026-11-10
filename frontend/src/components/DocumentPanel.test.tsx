import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { DocumentPanel } from './DocumentPanel'

const knowledgeBase = { id: '1', name: 'Java', createdAt: '', updatedAt: '' }

describe('DocumentPanel', () => {
  it('rejects unsupported input before starting an upload', () => {
    const onUploaded = vi.fn()
    const { container } = render(
      <DocumentPanel
        documents={[]}
        knowledgeBase={knowledgeBase}
        loading={false}
        onUploaded={onUploaded}
      />,
    )

    const input = container.querySelector('input[type="file"]') as HTMLInputElement
    fireEvent.change(input, { target: { files: [new File(['text'], 'notes.xlsx', { type: 'application/octet-stream' })] } })

    expect(screen.getByRole('alert')).toHaveTextContent('支持 PDF、PPTX、DOCX、TXT、Markdown、MP4、M4A、MP3 和 WAV 文件。')
    expect(onUploaded).not.toHaveBeenCalled()
  })
})
