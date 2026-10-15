import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ClinicalSummaryCard from './ClinicalSummaryCard.vue'
import type { ClinicalExtraction } from '../types'

const extraction: ClinicalExtraction = {
  extraction_id: 'extraction-1', version_no: 1, status: 'GENERATED', snapshot_id: 'snapshot-1', snapshot_hash: 'hash-1',
  fields: {
    chief_complaint: { value: '头痛三天', confidence: 95, evidence: [] },
    onset_course: { value: '三天前开始头痛', confidence: 95, evidence: [] },
    associated_symptoms: { value: '伴恶心', confidence: 95, evidence: [] },
    allergy_history: { value: '青霉素过敏', confidence: 95, evidence: [] }
  }, quality_issues: [], generated_at: null, confirmed_at: null, provider_route: 'LOCAL', source_route: 'LOCAL',
  available_routes: ['LOCAL'], route_selection_required: false
}
const props = { extraction, snapshotId: 'snapshot-1', snapshotHash: 'hash-1', roleReviewCount: 0,
  allTranscribed: true, readOnly: false, busy: false, error: '' }

describe('clinical summary', () => {
  it('displays the existing extraction in three sections and exposes evidence and confirmation', async () => {
    const wrapper = mount(ClinicalSummaryCard, { props })
    expect(wrapper.get('.clinical-summary-chief').text()).toBe('头痛三天')
    expect(wrapper.text()).toContain('三天前开始头痛；伴恶心')
    expect(wrapper.text()).toContain('过敏史青霉素过敏')
    const buttons = wrapper.findAll('button')
    await buttons[0].trigger('click')
    await buttons[1].trigger('click')
    expect(wrapper.emitted('details')).toHaveLength(1)
    expect(wrapper.emitted('confirm')).toHaveLength(1)
  })

  it('hides all facts when either snapshot id or hash has changed', () => {
    for (const changed of [{ snapshotId: 'snapshot-2' }, { snapshotHash: 'hash-2' }, { allTranscribed: false }]) {
      const wrapper = mount(ClinicalSummaryCard, { props: { ...props, ...changed } })
      expect(wrapper.text()).not.toContain('头痛三天')
      expect(wrapper.text()).not.toContain('青霉素过敏')
    }
  })

  it('keeps pending extraction and role review distinct and only offers role review when necessary', async () => {
    const wrapper = mount(ClinicalSummaryCard, { props: { ...props, roleReviewCount: 2,
      extraction: { ...extraction, status: 'FAILED', fields: {}, quality_issues: ['角色需核对'] } } })
    expect(wrapper.get('[role="status"]').text()).toBe('待角色核对')
    expect(wrapper.text()).toContain('还有 2 条角色待核对')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('review')).toHaveLength(1)
    await wrapper.setProps({ roleReviewCount: 0, extraction: { ...extraction, status: 'RUNNING', fields: {} } })
    expect(wrapper.attributes('aria-busy')).toBe('true')
    expect(wrapper.findAll('button')).toHaveLength(0)
    expect(wrapper.text()).not.toContain('头痛三天')
  })

  it('shows an honest missing-information placeholder and respects read-only access', () => {
    const wrapper = mount(ClinicalSummaryCard, { props: { ...props, readOnly: true,
      extraction: { ...extraction, fields: { chief_complaint: extraction.fields.chief_complaint } } } })
    expect(wrapper.text()).toContain('本次对话未提及，待补充。')
    expect(wrapper.text()).not.toContain('确认提取结果')
    expect(wrapper.text()).not.toContain('无过敏史')
  })

  it('offers an explicit retry after failure', async () => {
    const wrapper = mount(ClinicalSummaryCard, { props: { ...props,
      extraction: { ...extraction, status: 'FAILED', fields: {}, quality_issues: ['模型暂时不可用'] } } })
    expect(wrapper.get('[role="alert"]').text()).toContain('模型暂时不可用')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })
})
