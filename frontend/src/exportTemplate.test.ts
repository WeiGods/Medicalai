import { readFile } from 'node:fs/promises'
import { beforeEach, expect, it, vi } from 'vitest'
import { TEMPLATES, type RecordExportData } from './exportTemplate'

const data: RecordExportData = {
  visitNo: 'V20260920001',
  versionNo: 1,
  confirmedAt: '2026-09-20T15:00:00Z',
  content: {
    name: '测试患者', gender: '男', age: 32, phone: '13800138000',
    chief: '咳嗽三天', present: '患者三天前受凉后咳嗽。', past: '既往体健。',
    opinion: '继续观察，必要时复诊。', medication: '暂无。', followup: '一周后复诊。',
    doctor: '测试医生', date: '2026-09-20'
  }
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async () => ({
    ok: true,
    arrayBuffer: async () => (await readFile('../backend/src/main/resources/fonts/NotoSansSC-Regular.ttf')).buffer
  })))
})

it('generates a valid Word document for every template', async () => {
  for (const template of TEMPLATES) {
    const blob = await template.createDocx(data)
    const header = Buffer.from(await blob.arrayBuffer()).subarray(0, 4)
    expect(blob.size).toBeGreaterThan(10000)
    expect(header.toString('latin1')).toBe('PK\x03\x04')
  }
})

it('generates a valid PDF document for every template', async () => {
  for (const template of TEMPLATES) {
    const blob = await template.createPdf(data)
    const header = Buffer.from(await blob.arrayBuffer()).subarray(0, 4)
    expect(blob.size).toBeGreaterThan(10000)
    expect(header.toString('latin1')).toBe('%PDF')
  }
})
