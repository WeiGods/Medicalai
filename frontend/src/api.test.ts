import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './api'

describe('api response handling', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
  })

  it('accepts a successful DELETE with a 200 empty response body', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(api.deleteRecording('visit-1', 'recording-1')).resolves.toBeUndefined()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/visits/visit-1/recordings/recording-1',
      expect.objectContaining({ method: 'DELETE' })
    )
  })

  it('accepts a successful DELETE with a 204 response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })))

    await expect(api.deleteRecording('visit-1', 'recording-1')).resolves.toBeUndefined()
  })

  it('sends logical template deletion through the management API', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(api.deleteTemplate('template-1')).resolves.toBeUndefined()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/template-management/templates/template-1',
      expect.objectContaining({ method: 'DELETE' })
    )
  })

  it('parses non-empty JSON responses as before', async () => {
    const recordings = [{ id: 'recording-1', status: 'DONE' }]
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(recordings), {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    })))

    await expect(api.recordings('visit-1')).resolves.toEqual(recordings)
  })

  it('preserves the server message for error responses', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ message: '录音不存在' }), {
      status: 404,
      headers: { 'Content-Type': 'application/json' }
    })))

    await expect(api.deleteRecording('visit-1', 'recording-1')).rejects.toThrow('录音不存在')
  })
})
