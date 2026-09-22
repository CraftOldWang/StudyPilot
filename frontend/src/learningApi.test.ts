import { afterEach, describe, expect, it, vi } from 'vitest'
import { learningApi } from './learningApi'

describe('learning API contract', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('preserves a database ID beyond Number.MAX_SAFE_INTEGER when opening a chat from an outline', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 0,
      message: 'ok',
      data: { traceId: 'trace-1', session: {} },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)

    await learningApi.newConversation('9007199254740993')

    expect(fetchMock).toHaveBeenCalledWith('/api/learning/plans/9007199254740993/session?newConversation=true', expect.objectContaining({
      method: 'POST',
    }))
  })

})
