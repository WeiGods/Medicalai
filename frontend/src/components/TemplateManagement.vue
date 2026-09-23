<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '../api'
import Icon from './Icon.vue'
import { showMessage } from '../message'
import type { ExportTemplate, ExportTemplateRevision } from '../types'

type TemplateField = { key: string; label: string; visible: boolean }
type TemplateSection = { key: string; title: string; layout: 'GRID_2' | 'TABLE'; fields: TemplateField[] }
type HeaderAppearance = { enabled: boolean; content: 'NONE' | 'TITLE' | 'TITLE_WITH_VERSION'; alignment: 'LEFT' | 'CENTER' | 'RIGHT' | 'SPLIT' }
type FooterAppearance = { enabled: boolean; content: 'NONE' | 'PAGE_X_OF_Y'; alignment: 'LEFT' | 'CENTER' | 'RIGHT' }
type MetadataAppearance = { position: 'NONE' | 'BELOW_TITLE'; fields: Array<'VISIT_NO' | 'RECORD_VERSION' | 'CONFIRMED_AT'> }
type FormatAppearance = { header: HeaderAppearance; footer: FooterAppearance; metadata: MetadataAppearance; basicInfo: 'INLINE_PAIR' }
type Appearance = {
  theme: 'MEDICAL_GREEN' | 'CLINICAL_BLUE' | 'MINIMAL_GRAY'
  table: { border: 'THIN'; cellPadding: number; labelColumnRatio: number; sectionHeader: 'SHADED_ROW' | 'TEXT_LABEL' | 'NONE' }
  docx: FormatAppearance
  pdf: FormatAppearance
}
type TemplateDefinition = {
  documentTitle: string
  layout: 'MERGED_TABLE' | 'SEPARATE_TABLES'
  page: { marginTop: number; marginRight: number; marginBottom: number; marginLeft: number }
  style: { fontSize: number; lineHeight: number; labelSize: number; sectionSize: number; cellPadding: number }
  headerEnabled: boolean
  footerEnabled: boolean
  appearance: Appearance
  sections: TemplateSection[]
}

const templates = ref<ExportTemplate[]>([])
const selectedId = ref('')
const title = ref('')
const description = ref('')
const revisions = ref<ExportTemplateRevision[]>([])
const busy = ref(false)
const historyOpen = ref(false)
const previewOpen = ref(false)
const previewUrl = ref('')
const creating = ref(false)
const deleteOpen = ref(false)

const selected = computed(() => templates.value.find(item => item.id === selectedId.value) || null)
const canDeleteSelectedTemplate = computed(() => {
  const item = selected.value
  return !!item && !item.default_template && !['v6', 'v7', 'v8'].includes(item.template_key)
})
const usedFields = computed(() => new Set(definition.value.sections.flatMap(section => section.fields.map(field => field.key))))
const availableFields = [
  ['name', '患者姓名'], ['gender', '性别'], ['age', '年龄'], ['phone', '联系方式'], ['chief', '患者主诉'],
  ['present', '患者现病史'], ['past', '患者既往史'], ['opinion', '医生处理意见'], ['medication', '用药情况'],
  ['followup', '复诊建议'], ['doctor', '接诊医生'], ['date', '接诊日期'], ['visit_no', '接诊编号'],
  ['record_version', '病历版本'], ['confirmed_at', '确认时间']
] as const
const metadataFields = [
  ['VISIT_NO', '接诊编号'], ['RECORD_VERSION', '病历版本'], ['CONFIRMED_AT', '确认时间']
] as const
const coreFields = new Set(['name', 'gender', 'age', 'phone', 'chief', 'doctor', 'date'])
const definition = ref<TemplateDefinition>(defaultDefinition())

function defaultFormat(kind: 'docx' | 'pdf'): FormatAppearance {
  return {
    header: { enabled: true, content: kind === 'pdf' ? 'TITLE_WITH_VERSION' : 'TITLE', alignment: kind === 'pdf' ? 'SPLIT' : 'CENTER' },
    footer: { enabled: true, content: 'PAGE_X_OF_Y', alignment: 'CENTER' },
    metadata: { position: 'BELOW_TITLE', fields: kind === 'pdf' ? ['VISIT_NO', 'CONFIRMED_AT'] : ['VISIT_NO', 'RECORD_VERSION', 'CONFIRMED_AT'] },
    basicInfo: 'INLINE_PAIR'
  }
}

function defaultDefinition(): TemplateDefinition {
  return {
    documentTitle: '门诊病历', layout: 'MERGED_TABLE',
    page: { marginTop: 60, marginRight: 54, marginBottom: 50, marginLeft: 54 },
    style: { fontSize: 9.5, lineHeight: 13.5, labelSize: 9, sectionSize: 10, cellPadding: 4 },
    headerEnabled: true, footerEnabled: true,
    appearance: {
      theme: 'MEDICAL_GREEN',
      table: { border: 'THIN', cellPadding: 4, labelColumnRatio: 24, sectionHeader: 'SHADED_ROW' },
      docx: defaultFormat('docx'), pdf: defaultFormat('pdf')
    },
    sections: [
      { key: 'basic', title: '一、基本信息', layout: 'GRID_2', fields: fields(['name', 'gender', 'age', 'phone']) },
      { key: 'visit', title: '二、就诊内容', layout: 'TABLE', fields: fields(['chief', 'present', 'past']) },
      { key: 'treatment', title: '三、诊疗记录', layout: 'TABLE', fields: fields(['opinion', 'medication', 'followup']) },
      { key: 'sign', title: '四、签署信息', layout: 'TABLE', fields: fields(['doctor', 'date', 'record_version', 'confirmed_at']) }
    ]
  }
}

function fields(keys: string[]) { return keys.map(key => ({ key, label: availableLabel(key), visible: true })) }
function availableLabel(key: string) { return availableFields.find(item => item[0] === key)?.[1] || key }

function normalizeFormat(value: Partial<FormatAppearance> | undefined, kind: 'docx' | 'pdf'): FormatAppearance {
  const fallback = defaultFormat(kind)
  return {
    ...fallback,
    ...value,
    header: { ...fallback.header, ...value?.header },
    footer: { ...fallback.footer, ...value?.footer },
    metadata: { ...fallback.metadata, ...value?.metadata, fields: Array.isArray(value?.metadata?.fields) ? value.metadata.fields : fallback.metadata.fields }
  }
}

function normalizeDefinition(value: Partial<TemplateDefinition> | undefined): TemplateDefinition {
  const fallback = defaultDefinition()
  const appearance = value?.appearance
  return {
    ...fallback,
    ...value,
    page: { ...fallback.page, ...value?.page },
    style: { ...fallback.style, ...value?.style },
    appearance: {
      ...fallback.appearance,
      ...appearance,
      table: { ...fallback.appearance.table, ...appearance?.table },
      docx: normalizeFormat(appearance?.docx, 'docx'),
      pdf: normalizeFormat(appearance?.pdf, 'pdf')
    },
    sections: Array.isArray(value?.sections) && value.sections.length ? value.sections : fallback.sections
  }
}

function parseDefinition(source?: string | null): TemplateDefinition {
  try { return normalizeDefinition(source ? JSON.parse(source) as Partial<TemplateDefinition> : undefined) }
  catch { return defaultDefinition() }
}

async function load(preselect = true) {
  busy.value = true
  try {
    templates.value = await api.managedTemplates()
    if (preselect && templates.value.length) selectTemplate(selectedId.value || templates.value[0].id)
  } catch (error) {
    showMessage(error instanceof Error ? error.message : '模板列表加载失败', 'error')
  } finally { busy.value = false }
}

function selectTemplate(id: string) {
  const item = templates.value.find(template => template.id === id)
  if (!item) return
  selectedId.value = id
  creating.value = false
  title.value = item.name
  description.value = item.description
  definition.value = parseDefinition(item.definition_json)
  closePreview()
}

function newTemplate() {
  const compactPreset = templates.value.find(template => template.template_key === 'v6')
  if (!compactPreset?.definition_json) {
    showMessage('未找到紧凑单页预设，无法新建模板', 'error')
    return
  }
  creating.value = true
  selectedId.value = ''
  title.value = ''
  description.value = ''
  definition.value = parseDefinition(compactPreset.definition_json)
  revisions.value = []
  closePreview()
}

async function save() {
  if (!title.value.trim()) return showMessage('请填写模板名称', 'error')
  busy.value = true
  try {
    definition.value = normalizeDefinition(definition.value)
    const definitionJson = JSON.stringify(definition.value)
    if (creating.value) {
      const created = await api.createTemplate({ name: title.value.trim(), description: description.value.trim(), definition_json: definitionJson })
      selectedId.value = created.id
      creating.value = false
    } else if (selected.value) {
      await api.saveTemplate(selected.value.id, { current_revision_id: selected.value.current_revision_id, definition_json: definitionJson })
    }
    await load(false)
    if (selectedId.value) selectTemplate(selectedId.value)
    showMessage('已保存为新版本，并立即生效', 'success')
  } catch (error) {
    showMessage(error instanceof Error ? error.message : '模板保存失败', 'error')
  } finally { busy.value = false }
}

async function toggleStatus(item: ExportTemplate) {
  busy.value = true
  try { await api.setTemplateStatus(item.id, item.status !== 'ACTIVE'); await load(false); if (selectedId.value) selectTemplate(selectedId.value) }
  catch (error) { showMessage(error instanceof Error ? error.message : '状态更新失败', 'error') } finally { busy.value = false }
}

async function setDefault(item: ExportTemplate) {
  busy.value = true
  try { await api.setDefaultTemplate(item.id); await load(false); selectTemplate(item.id) }
  catch (error) { showMessage(error instanceof Error ? error.message : '默认模板更新失败', 'error') } finally { busy.value = false }
}

function deleteDisabledReason() {
  if (!selected.value) return '请先选择模板'
  if (selected.value.default_template) return '默认模板不能删除，请先设置另一个启用模板为默认模板'
  if (['v6', 'v7', 'v8'].includes(selected.value.template_key)) return '系统初始模板不能删除'
  return '删除模板'
}

function requestDelete() {
  if (!canDeleteSelectedTemplate.value) return
  deleteOpen.value = true
}

async function deleteTemplate() {
  if (!selected.value || !canDeleteSelectedTemplate.value) return
  const templateId = selected.value.id
  busy.value = true
  try {
    await api.deleteTemplate(templateId)
    deleteOpen.value = false
    closePreview()
    selectedId.value = ''
    await load(false)
    if (templates.value.length) selectTemplate(templates.value[0].id)
    else {
      creating.value = false
      title.value = ''
      description.value = ''
      definition.value = defaultDefinition()
    }
    showMessage('模板已删除，历史导出不受影响', 'success')
  } catch (error) {
    showMessage(error instanceof Error ? error.message : '模板删除失败', 'error')
  } finally { busy.value = false }
}

async function openHistory() {
  if (!selected.value) return
  busy.value = true
  try { revisions.value = await api.templateRevisions(selected.value.id); historyOpen.value = true }
  catch (error) { showMessage(error instanceof Error ? error.message : '版本历史加载失败', 'error') } finally { busy.value = false }
}

async function restore(revision: ExportTemplateRevision) {
  if (!selected.value) return
  busy.value = true
  try {
    await api.restoreTemplateRevision(selected.value.id, revision.id, selected.value.current_revision_id)
    historyOpen.value = false
    await load(false)
    selectTemplate(selected.value.id)
    showMessage('历史版本已恢复为新的当前版本', 'success')
  } catch (error) { showMessage(error instanceof Error ? error.message : '版本恢复失败', 'error') } finally { busy.value = false }
}

async function preview() {
  busy.value = true
  try {
    closePreview()
    definition.value = normalizeDefinition(definition.value)
    previewUrl.value = URL.createObjectURL(await api.draftTemplatePreviewBlob('PDF', JSON.stringify(definition.value)))
    previewOpen.value = true
  } catch (error) { showMessage(error instanceof Error ? error.message : '预览生成失败', 'error') } finally { busy.value = false }
}

function closePreview() {
  previewOpen.value = false
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = ''
}

function formatAppearance(kind: 'docx' | 'pdf') { return definition.value.appearance[kind] }
function syncHeader(kind: 'docx' | 'pdf') {
  const header = formatAppearance(kind).header
  if (!header.enabled) header.content = 'NONE'
  else if (header.content === 'NONE') header.content = kind === 'pdf' ? 'TITLE_WITH_VERSION' : 'TITLE'
}
function syncFooter(kind: 'docx' | 'pdf') {
  const footer = formatAppearance(kind).footer
  footer.content = footer.enabled ? 'PAGE_X_OF_Y' : 'NONE'
}
function syncMetadata(kind: 'docx' | 'pdf') {
  const metadata = formatAppearance(kind).metadata
  if (metadata.position === 'NONE') metadata.fields = []
  else if (!metadata.fields.length) metadata.fields = kind === 'pdf' ? ['VISIT_NO'] : ['VISIT_NO', 'RECORD_VERSION']
}
function toggleMetadata(kind: 'docx' | 'pdf', field: MetadataAppearance['fields'][number]) {
  const metadata = formatAppearance(kind).metadata
  if (metadata.fields.includes(field)) metadata.fields = metadata.fields.filter(item => item !== field)
  else metadata.fields.push(field)
  if (!metadata.fields.length) metadata.position = 'NONE'
  else if (metadata.position === 'NONE') metadata.position = 'BELOW_TITLE'
}
function handleKeydown(event: KeyboardEvent) {
  if (event.key !== 'Escape') return
  if (previewOpen.value) closePreview()
  if (deleteOpen.value) deleteOpen.value = false
}
function addSection() { definition.value.sections.push({ key: `section_${Date.now()}`, title: '新章节', layout: 'TABLE', fields: [] }) }
function removeSection(index: number) { if (definition.value.sections.length > 1) definition.value.sections.splice(index, 1) }
function move<T>(items: T[], index: number, distance: number) {
  const target = index + distance
  if (target < 0 || target >= items.length) return
  const [item] = items.splice(index, 1); items.splice(target, 0, item)
}
function addField(section: TemplateSection) {
  const candidate = availableFields.find(([key]) => !usedFields.value.has(key))
  if (candidate) section.fields.push({ key: candidate[0], label: candidate[1], visible: true })
}
function removeField(section: TemplateSection, index: number) {
  const field = section.fields[index]
  if (!coreFields.has(field.key)) section.fields.splice(index, 1)
}

onMounted(() => { void load(); window.addEventListener('keydown', handleKeydown) })
onBeforeUnmount(() => { window.removeEventListener('keydown', handleKeydown); closePreview() })
</script>

<template>
  <section class="template-management" aria-label="模板管理">
    <aside class="template-library">
      <div class="template-library-head"><div><span class="eyebrow">HOSPITAL LIBRARY</span><h2>导出模板</h2></div><button class="icon-btn" title="新建模板" aria-label="新建模板" @click="newTemplate"><Icon name="plus" /></button></div>
      <button v-for="item in templates" :key="item.id" class="template-list-item" :class="{ active: item.id === selectedId }" @click="selectTemplate(item.id)">
        <span><b>{{ item.name }}</b><small>v{{ item.current_revision_no }} · {{ item.status === 'ACTIVE' ? '启用' : '停用' }}</small></span><i v-if="item.default_template">默认</i>
      </button>
      <p v-if="!templates.length && !busy" class="small-muted">暂无可维护模板</p>
    </aside>

    <div class="template-editor">
      <div class="template-editor-head"><div><span class="eyebrow">{{ creating ? 'NEW TEMPLATE' : 'CURRENT REVISION' }}</span><h2>{{ creating ? '新建模板' : `${selected?.name || '选择模板'} · v${selected?.current_revision_no || '—'}` }}</h2></div><div class="template-actions"><button class="btn" :disabled="busy || creating || !selected" @click="openHistory"><Icon name="clock" />版本历史</button><button class="btn" :disabled="busy" @click="preview"><Icon name="eye" />预览</button><button class="btn primary" :disabled="busy" @click="save"><Icon name="save" />保存</button></div></div>
      <div class="template-meta-grid"><label>模板名称<input v-model="title" maxlength="80" /></label><label>说明<input v-model="description" maxlength="256" /></label></div>

      <section class="template-panel"><h3>文档</h3><div class="template-style-grid"><label>文档标题<input v-model="definition.documentTitle" maxlength="80" /></label><label>整体布局<select v-model="definition.layout"><option value="MERGED_TABLE">合并表格</option><option value="SEPARATE_TABLES">分节表格</option></select></label><label>正文大小<input v-model.number="definition.style.fontSize" type="number" min="8" max="16" /></label><label>行距<input v-model.number="definition.style.lineHeight" type="number" min="10" max="28" /></label><label>标签大小<input v-model.number="definition.style.labelSize" type="number" min="7" max="16" /></label><label>章节大小<input v-model.number="definition.style.sectionSize" type="number" min="8" max="18" /></label><label>上页边距<input v-model.number="definition.page.marginTop" type="number" min="36" max="90" /></label><label>右页边距<input v-model.number="definition.page.marginRight" type="number" min="36" max="90" /></label><label>下页边距<input v-model.number="definition.page.marginBottom" type="number" min="36" max="90" /></label><label>左页边距<input v-model.number="definition.page.marginLeft" type="number" min="36" max="90" /></label></div></section>

      <section class="template-panel"><h3>外观</h3><div class="template-style-grid"><label>主题色板<select v-model="definition.appearance.theme"><option value="MEDICAL_GREEN">医疗绿</option><option value="CLINICAL_BLUE">临床蓝</option><option value="MINIMAL_GRAY">简洁灰</option></select></label><div class="theme-preview" :class="definition.appearance.theme"><span></span><b>{{ definition.appearance.theme === 'MEDICAL_GREEN' ? '医疗绿' : definition.appearance.theme === 'CLINICAL_BLUE' ? '临床蓝' : '简洁灰' }}</b></div></div></section>

      <section class="template-panel"><h3>表格</h3><div class="template-style-grid"><label>边框<select v-model="definition.appearance.table.border"><option value="THIN">细边框</option></select></label><label>单元格留白<input v-model.number="definition.appearance.table.cellPadding" type="number" min="0" max="12" /></label><label>标签列宽比例<input v-model.number="definition.appearance.table.labelColumnRatio" type="number" min="20" max="40" /></label><label>分节标题<select v-model="definition.appearance.table.sectionHeader"><option value="SHADED_ROW">整行底纹</option><option value="TEXT_LABEL">文字标签</option><option value="NONE">不显示</option></select></label></div></section>

      <section class="template-panel"><h3>页眉页脚</h3><div class="format-grid">
        <div class="format-panel"><h4>Word</h4><div class="format-controls"><label class="switch-label"><input v-model="definition.appearance.docx.header.enabled" type="checkbox" @change="syncHeader('docx')" />页眉</label><label>页眉对齐<select v-model="definition.appearance.docx.header.alignment" :disabled="!definition.appearance.docx.header.enabled"><option value="LEFT">左对齐</option><option value="CENTER">居中</option><option value="RIGHT">右对齐</option></select></label><label class="switch-label"><input v-model="definition.appearance.docx.footer.enabled" type="checkbox" @change="syncFooter('docx')" />页脚页码</label><label>页脚对齐<select v-model="definition.appearance.docx.footer.alignment" :disabled="!definition.appearance.docx.footer.enabled"><option value="LEFT">左对齐</option><option value="CENTER">居中</option><option value="RIGHT">右对齐</option></select></label><label>元数据<select v-model="definition.appearance.docx.metadata.position" @change="syncMetadata('docx')"><option value="BELOW_TITLE">标题下方</option><option value="NONE">不显示</option></select></label><div class="metadata-options"><label v-for="[field, fieldLabel] in metadataFields" :key="field" class="switch-label"><input :checked="definition.appearance.docx.metadata.fields.includes(field)" type="checkbox" @change="toggleMetadata('docx', field)" />{{ fieldLabel }}</label></div></div></div>
        <div class="format-panel"><h4>PDF</h4><div class="format-controls"><label class="switch-label"><input v-model="definition.appearance.pdf.header.enabled" type="checkbox" @change="syncHeader('pdf')" />页眉</label><label>页眉内容<select v-model="definition.appearance.pdf.header.content" :disabled="!definition.appearance.pdf.header.enabled"><option value="TITLE_WITH_VERSION">标题和版本号</option><option value="TITLE">仅标题</option></select></label><label class="switch-label"><input v-model="definition.appearance.pdf.footer.enabled" type="checkbox" @change="syncFooter('pdf')" />页脚页码</label><label>页脚对齐<select v-model="definition.appearance.pdf.footer.alignment" :disabled="!definition.appearance.pdf.footer.enabled"><option value="LEFT">左对齐</option><option value="CENTER">居中</option><option value="RIGHT">右对齐</option></select></label><label>元数据<select v-model="definition.appearance.pdf.metadata.position" @change="syncMetadata('pdf')"><option value="BELOW_TITLE">标题下方</option><option value="NONE">不显示</option></select></label><div class="metadata-options"><label v-for="[field, fieldLabel] in metadataFields" :key="field" class="switch-label"><input :checked="definition.appearance.pdf.metadata.fields.includes(field)" type="checkbox" @change="toggleMetadata('pdf', field)" />{{ fieldLabel }}</label></div></div></div>
      </div></section>

      <section class="template-panel"><div class="panel-heading"><h3>章节与字段</h3><button class="icon-btn" title="添加章节" aria-label="添加章节" @click="addSection"><Icon name="plus" /></button></div>
        <article v-for="(section, sectionIndex) in definition.sections" :key="section.key" class="template-section-editor"><div class="section-editor-head"><input v-model="section.title" maxlength="80" /><select v-model="section.layout"><option value="GRID_2">双列信息</option><option value="TABLE">标签表格</option></select><button class="icon-btn" title="上移章节" aria-label="上移章节" @click="move(definition.sections, sectionIndex, -1)"><Icon name="arrow-left" /></button><button class="icon-btn rotate" title="下移章节" aria-label="下移章节" @click="move(definition.sections, sectionIndex, 1)"><Icon name="arrow" /></button><button class="icon-btn" title="删除章节" aria-label="删除章节" :disabled="definition.sections.length === 1" @click="removeSection(sectionIndex)"><Icon name="trash" /></button></div><div class="field-list"><div v-for="(field, fieldIndex) in section.fields" :key="field.key" class="template-field-row"><select v-model="field.key" disabled><option :value="field.key">{{ availableLabel(field.key) }}</option></select><input v-model="field.label" maxlength="80" /><label class="switch-label"><input v-model="field.visible" type="checkbox" />显示</label><button class="icon-btn" title="上移字段" aria-label="上移字段" @click="move(section.fields, fieldIndex, -1)"><Icon name="arrow-left" /></button><button class="icon-btn rotate" title="下移字段" aria-label="下移字段" @click="move(section.fields, fieldIndex, 1)"><Icon name="arrow" /></button><button class="icon-btn" :disabled="coreFields.has(field.key)" title="移除字段" aria-label="移除字段" @click="removeField(section, fieldIndex)"><Icon name="trash" /></button></div><button class="field-add" :disabled="usedFields.size >= availableFields.length" @click="addField(section)"><Icon name="plus" />添加字段</button></div></article>
      </section>
      <div v-if="selected && !creating" class="template-state-actions"><span :class="['template-status', selected.status.toLowerCase()]">{{ selected.status === 'ACTIVE' ? '已启用' : '已停用' }}</span><button class="btn" :disabled="busy || selected.default_template" @click="setDefault(selected)">设为默认</button><button class="btn" :disabled="busy || selected.default_template" @click="toggleStatus(selected)">{{ selected.status === 'ACTIVE' ? '停用' : '启用' }}</button><button class="btn danger" aria-label="删除模板" :title="deleteDisabledReason()" :disabled="busy || !canDeleteSelectedTemplate" @click="requestDelete"><Icon name="trash" />删除</button></div>
    </div>

    <div v-if="historyOpen" class="history-backdrop" @click.self="historyOpen = false"><section class="history-dialog"><div class="panel-heading"><h2>版本历史</h2><button class="icon-btn" aria-label="关闭版本历史" @click="historyOpen = false"><Icon name="x" /></button></div><div v-for="revision in revisions" :key="revision.id" class="revision-row"><span><b>v{{ revision.revision_no }}</b><small>{{ revision.created_at }}</small></span><button v-if="revision.id !== selected?.current_revision_id" class="btn small" :disabled="busy" @click="restore(revision)"><Icon name="refresh" />恢复为新版本</button><i v-else>当前</i></div></section></div>
    <div v-if="previewOpen" class="preview-backdrop" role="presentation" @click.self="closePreview"><div class="preview-dialog" role="dialog" aria-modal="true" aria-label="模板预览"><div class="preview-head"><h2><Icon name="eye" />模板预览 · {{ title || '导出模板' }}</h2><button class="icon-btn" aria-label="关闭预览" @click="closePreview"><Icon name="x" /></button></div><iframe v-if="previewUrl" :src="previewUrl" class="preview-frame" title="PDF 预览"></iframe></div></div>
    <div v-if="deleteOpen" class="history-backdrop" role="presentation" @click.self="deleteOpen = false"><section class="template-confirm-dialog" role="dialog" aria-modal="true" aria-label="删除模板确认"><div class="panel-heading"><h2>删除模板</h2><button class="icon-btn" aria-label="关闭删除确认" @click="deleteOpen = false"><Icon name="x" /></button></div><p>确认删除“{{ selected?.name }}”吗？历史导出仍会保留原模板版本。</p><div class="template-confirm-actions"><button class="btn" :disabled="busy" @click="deleteOpen = false">取消</button><button class="btn danger" aria-label="确认删除模板" :disabled="busy" @click="deleteTemplate"><Icon name="trash" />删除模板</button></div></section></div>
  </section>
</template>

<style scoped>
.template-management{display:grid;grid-template-columns:255px minmax(0,1fr);gap:18px;min-height:620px}.template-library,.template-editor,.template-panel{border:1px solid #d9e1dc;background:#fff}.template-library{padding:14px}.template-library-head,.template-editor-head,.panel-heading,.section-editor-head,.template-state-actions{display:flex;align-items:center;justify-content:space-between;gap:10px}.template-library h2,.template-editor h2,.template-panel h3,.template-panel h4{margin:3px 0;color:#1f2e2b}.template-panel h4{font-size:14px}.template-list-item{width:100%;display:flex;justify-content:space-between;text-align:left;padding:12px 10px;margin-top:8px;border:1px solid #e5ebe7;background:#fff;border-radius:4px}.template-list-item.active{border-color:#3f8066;background:#f2f8f4}.template-list-item small,.revision-row small{display:block;margin-top:4px;color:#667c72}.template-list-item i{font-size:11px;color:#146c43;font-style:normal}.template-editor{padding:20px;min-width:0}.template-actions{display:flex;gap:8px}.template-meta-grid,.template-style-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.template-meta-grid{grid-template-columns:1fr 2fr;margin:18px 0}.template-management label{display:grid;gap:5px;color:#52645c;font-size:13px}.template-management input,.template-management select{min-width:0;box-sizing:border-box;border:1px solid #cfd9d3;border-radius:3px;background:#fff;padding:8px;color:#1f2e2b}.template-management select:disabled{background:#f1f4f2;color:#809087}.template-panel{margin-top:14px;padding:14px}.theme-preview{display:flex;align-items:center;align-self:end;gap:8px;height:34px;color:#52645c;font-size:13px}.theme-preview span{display:block;width:26px;height:26px;border-radius:3px;background:#3f8066}.theme-preview.CLINICAL_BLUE span{background:#3379a7}.theme-preview.MINIMAL_GRAY span{background:#687076}.format-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.format-panel{border:1px solid #e1e7e3;padding:12px}.format-controls{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.metadata-options{grid-column:1/-1;display:flex;gap:10px;flex-wrap:wrap}.switch-label{display:flex!important;align-items:center;gap:5px!important;white-space:nowrap}.switch-label input{padding:0}.template-section-editor{border-top:1px solid #e3e9e5;padding:12px 0}.section-editor-head{justify-content:flex-start}.section-editor-head>input{flex:1}.section-editor-head>select{width:120px}.field-list{margin:10px 0 0 8px}.template-field-row{display:grid;grid-template-columns:150px minmax(120px,1fr) 62px repeat(3,32px);gap:7px;align-items:center;margin:7px 0}.icon-btn{display:inline-grid;place-items:center;width:32px;height:32px;border:1px solid #cfd9d3;border-radius:3px;background:#fff;color:#355647}.icon-btn svg,.field-add svg{width:16px;height:16px}.rotate{transform:rotate(90deg)}.field-add{display:inline-flex;align-items:center;gap:5px;border:0;background:none;color:#236e50;padding:6px 0}.template-state-actions{margin-top:14px;justify-content:flex-start;flex-wrap:wrap}.template-status{padding:5px 8px;border-radius:3px;font-size:12px}.template-status.active{background:#e5f4eb;color:#146c43}.template-status.disabled{background:#f5eeee;color:#94505a}.history-backdrop,.preview-backdrop{position:fixed;inset:0;background:#18312655;display:grid;place-items:center;z-index:20}.history-dialog{width:min(520px,calc(100vw - 32px));max-height:70vh;overflow:auto;background:#fff;padding:18px;border-radius:6px}.template-confirm-dialog{width:min(460px,calc(100vw - 32px));background:#fff;padding:18px;border-radius:6px}.template-confirm-dialog p{margin:16px 0;color:#52645c;font-size:13px;line-height:1.6}.template-confirm-actions{display:flex;justify-content:flex-end;gap:8px}.revision-row{display:flex;align-items:center;justify-content:space-between;padding:12px 0;border-top:1px solid #e5ebe7}.revision-row i{font-style:normal;color:#236e50;font-size:13px}.preview-dialog{width:min(960px,calc(100vw - 32px));height:min(82vh,840px);background:#fff;border-radius:6px;display:flex;flex-direction:column}.preview-head{display:flex;align-items:center;justify-content:space-between;padding:14px 16px;border-bottom:1px solid #e1e7e3}.preview-head h2{display:flex;align-items:center;gap:7px;margin:0;color:#1f2e2b;font-size:17px}.preview-head svg{width:18px}.preview-frame{border:0;min-height:0;flex:1;width:100%}@media(max-width:900px){.template-management{grid-template-columns:1fr}.template-meta-grid,.template-style-grid,.format-grid{grid-template-columns:1fr 1fr}.template-field-row{grid-template-columns:120px minmax(90px,1fr) 56px repeat(3,30px)}.template-editor-head{align-items:flex-start;flex-direction:column}.template-actions{flex-wrap:wrap}}@media(max-width:620px){.template-meta-grid,.template-style-grid,.format-grid,.format-controls{grid-template-columns:1fr}.template-field-row{grid-template-columns:1fr 1fr 58px repeat(3,30px)}.metadata-options{grid-column:auto}}
</style>
