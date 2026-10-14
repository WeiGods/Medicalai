import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import TemplateManagement from './TemplateManagement.vue'
import { api } from '../api'
import type { ExportTemplate } from '../types'

vi.mock('../api', () => ({
  api: {
    managedTemplates: vi.fn(),
    draftTemplatePreviewBlob: vi.fn(),
    createTemplate: vi.fn(),
    saveTemplate: vi.fn(),
    deleteTemplate: vi.fn()
  }
}))

const template: ExportTemplate = {
  id: 'template-1', template_key: 'standard-record', name: '标准病历', description: '标准版式',
  status: 'ACTIVE', default_template: true, current_revision_id: 'revision-1', current_revision_no: 6,
  definition_json: JSON.stringify({
    documentTitle: '门诊病历', layout: 'MERGED_TABLE',
    page: { marginTop: 60, marginRight: 54, marginBottom: 50, marginLeft: 54 },
    style: { fontSize: 10, lineHeight: 14, labelSize: 9, sectionSize: 10, cellPadding: 4 },
    headerEnabled: true, footerEnabled: true, sections: []
  }),
  updated_at: '2026-09-22T08:00:00Z'
}

describe('TemplateManagement preview', () => {
  const createObjectUrl = vi.fn(() => 'blob:template-preview')
  const revokeObjectUrl = vi.fn()

  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('URL', { createObjectURL: createObjectUrl, revokeObjectURL: revokeObjectUrl })
    vi.mocked(api.managedTemplates).mockResolvedValue([template])
    vi.mocked(api.draftTemplatePreviewBlob).mockResolvedValue(new Blob(['pdf'], { type: 'application/pdf' }))
  })

  afterEach(() => vi.unstubAllGlobals())

  it('opens the generated PDF in a modal and revokes its URL when closed', async () => {
    const wrapper = mount(TemplateManagement, { props: { doctorName: '王医生' } })
    await flushPromises()

    const previewButton = wrapper.findAll('button').find(button => button.text().includes('预览'))
    expect(previewButton).toBeDefined()
    await previewButton!.trigger('click')
    await flushPromises()

    expect(api.draftTemplatePreviewBlob).toHaveBeenCalledWith('PDF', expect.stringContaining('"documentTitle":"门诊病历"'))
    expect(wrapper.get('[role="dialog"][aria-label="模板预览"]').exists()).toBe(true)
    expect(wrapper.get('iframe[title="PDF 预览"]').attributes('src')).toBe('blob:template-preview')

    await wrapper.get('button[aria-label="关闭预览"]').trigger('click')

    expect(wrapper.find('[role="dialog"][aria-label="模板预览"]').exists()).toBe(false)
    expect(revokeObjectUrl).toHaveBeenCalledWith('blob:template-preview')
  })

  it('closes the preview when Escape is pressed', async () => {
    const wrapper = mount(TemplateManagement, { props: { doctorName: '王医生' } })
    await flushPromises()
    const previewButton = wrapper.findAll('button').find(button => button.text().includes('预览'))
    expect(previewButton).toBeDefined()
    await previewButton!.trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="dialog"][aria-label="模板预览"]').exists()).toBe(true)

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flushPromises()

    expect(wrapper.find('[role="dialog"][aria-label="模板预览"]').exists()).toBe(false)
  })

  it('uses the database v6 definition as the new-template preset and previews it before saving', async () => {
    const compact = { ...template, id: 'template-v6', template_key: 'v6', name: '紧凑单页' }
    vi.mocked(api.managedTemplates).mockResolvedValue([template, compact])
    const wrapper = mount(TemplateManagement, { props: { doctorName: '王医生' } })
    await flushPromises()

    await wrapper.get('button[aria-label="新建模板"]').trigger('click')
    const previewButton = wrapper.findAll('button').find(button => button.text().includes('预览'))
    await previewButton!.trigger('click')
    await flushPromises()

    expect(api.draftTemplatePreviewBlob).toHaveBeenCalledWith('PDF', expect.stringContaining('"layout":"MERGED_TABLE"'))
    expect(api.createTemplate).not.toHaveBeenCalled()
    expect(api.saveTemplate).not.toHaveBeenCalled()
  })

  it('deletes a non-default user template only after confirmation', async () => {
    const compact = { ...template, id: 'template-v6', template_key: 'v6', name: '紧凑单页' }
    const disposable = { ...template, id: 'template-custom', template_key: 'custom-rounding', name: '测试模板', default_template: false }
    vi.mocked(api.managedTemplates)
      .mockResolvedValueOnce([compact, disposable])
      .mockResolvedValueOnce([compact])
    vi.mocked(api.deleteTemplate).mockResolvedValue(undefined)
    const wrapper = mount(TemplateManagement, { props: { doctorName: '王医生' } })
    await flushPromises()

    await wrapper.findAll('.template-list-item')[1].trigger('click')
    const deleteButton = wrapper.get('button[aria-label="删除模板"]')
    expect(deleteButton.attributes('disabled')).toBeUndefined()
    await deleteButton.trigger('click')
    expect(wrapper.get('[role="dialog"][aria-label="删除模板确认"]').exists()).toBe(true)

    await wrapper.get('button[aria-label="确认删除模板"]').trigger('click')
    await flushPromises()

    expect(api.deleteTemplate).toHaveBeenCalledWith('template-custom')
    expect(wrapper.text()).not.toContain('测试模板')
  })
})
