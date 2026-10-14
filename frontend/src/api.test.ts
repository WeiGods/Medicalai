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

  it('sends the role-recognition route as role_provider with the ASR provider', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ job_id: 'job-1' }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    }))
    vi.stubGlobal('fetch', fetchMock)

    await api.transcribe('visit-1', 'LOCAL', 'DASHSCOPE')

    expect(fetchMock).toHaveBeenCalledWith('/api/v1/visits/visit-1/recordings/transcribe', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ provider: 'LOCAL', role_provider: 'DASHSCOPE' })
    }))
  })

  it('sends role_provider as null when the role route follows the backend default', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ job_id: 'job-2' }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    }))
    vi.stubGlobal('fetch', fetchMock)

    await api.retranscribeRecording('visit-1', 'recording-1', 'DASHSCOPE', null)

    expect(fetchMock).toHaveBeenCalledWith('/api/v1/visits/visit-1/recordings/recording-1/retranscribe', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ provider: 'DASHSCOPE', role_provider: null })
    }))
  })
})
