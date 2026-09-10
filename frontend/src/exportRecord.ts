export interface ExportField {
  section: string
  label: string
  value: string
}

export interface ExportRecord {
  patientName: string
  visitId: string
  recordId: string
  version: string
  doctor: string
  confirmedAt: string
  fields: ExportField[]
}

function xml(value: unknown) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;')
}

function paragraph(text: unknown, bold = false, size = 22) {
  const run = String(text ?? '').split(/\r?\n/).map(line => `<w:t xml:space="preserve">${xml(line)}</w:t>`).join('<w:br/>')
  const props = `${bold ? '<w:b/>' : ''}<w:sz w:val="${size}"/><w:szCs w:val="${size}"/>`
  return `<w:p><w:r><w:rPr>${props}</w:rPr>${run}</w:r></w:p>`
}

const crcTable = (() => {
  const table = new Uint32Array(256)
  for (let n = 0; n < 256; n++) {
    let value = n
    for (let bit = 0; bit < 8; bit++) value = value & 1 ? 0xedb88320 ^ (value >>> 1) : value >>> 1
    table[n] = value >>> 0
  }
  return table
})()

function crc32(data: Uint8Array) {
  let crc = 0xffffffff
  for (const byte of data) crc = crcTable[(crc ^ byte) & 255] ^ (crc >>> 8)
  return (crc ^ 0xffffffff) >>> 0
}

function u16(target: Uint8Array, offset: number, value: number) {
  target[offset] = value & 255
  target[offset + 1] = value >>> 8
}

function u32(target: Uint8Array, offset: number, value: number) {
  target[offset] = value & 255
  target[offset + 1] = (value >>> 8) & 255
  target[offset + 2] = (value >>> 16) & 255
  target[offset + 3] = (value >>> 24) & 255
}

function zip(entries: { name: string; data: string }[]) {
  const locals: Uint8Array[] = []
  const central: Uint8Array[] = []
  let offset = 0
  let total = 0
  for (const entry of entries) {
    const name = new TextEncoder().encode(entry.name)
    const data = new TextEncoder().encode(entry.data)
    const checksum = crc32(data)
    const local = new Uint8Array(30 + name.length + data.length)
    u32(local, 0, 0x04034b50)
    u16(local, 4, 20)
    u16(local, 6, 0x800)
    u32(local, 14, checksum)
    u32(local, 18, data.length)
    u32(local, 22, data.length)
    u16(local, 26, name.length)
    local.set(name, 30)
    local.set(data, 30 + name.length)
    locals.push(local)
    total += local.length
    const item = new Uint8Array(46 + name.length)
    u32(item, 0, 0x02014b50)
    u16(item, 4, 20)
    u16(item, 6, 20)
    u16(item, 8, 0x800)
    u32(item, 16, checksum)
    u32(item, 20, data.length)
    u32(item, 24, data.length)
    u16(item, 28, name.length)
    u32(item, 38, offset)
    item.set(name, 46)
    central.push(item)
    offset += local.length
  }
  const centralSize = central.reduce((sum, item) => sum + item.length, 0)
  const end = new Uint8Array(22)
  const output = new Uint8Array(total + centralSize + 22)
  let at = 0
  for (const item of locals) { output.set(item, at); at += item.length }
  for (const item of central) { output.set(item, at); at += item.length }
  u32(end, 0, 0x06054b50)
  u16(end, 8, entries.length)
  u16(end, 10, entries.length)
  u32(end, 12, centralSize)
  u32(end, 16, total)
  output.set(end, at)
  return output
}

function documentXml(record: ExportRecord) {
  let body = paragraph('病历记录', true, 32)
  const metadata = [['患者姓名', record.patientName], ['接诊编号', record.visitId], ['病历编号', record.recordId], ['版本', record.version], ['接诊医生', record.doctor], ['确认时间', record.confirmedAt]]
  for (const [label, value] of metadata) body += paragraph(`${label}：${value ?? ''}`)
  let section = ''
  for (const field of record.fields) {
    if (field.section && field.section !== section) { body += paragraph(field.section, true, 24); section = field.section }
    body += paragraph(`${field.label}：${field.value ?? ''}`)
  }
  body += '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr>'
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>${body}</w:body></w:document>`
}

function safeName(record: ExportRecord) {
  const name = String(record.patientName || '病历').replace(/[\\/:*?"<>|\x00-\x1f]/g, '_').trim() || '病历'
  const id = String(record.visitId || '').replace(/[^\w一-龥-]/g, '_')
  return `${id ? `${name}_${id}` : name}.docx`
}

export function downloadDocx(record: ExportRecord) {
  const contentTypes = '<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>'
  const rels = '<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>'
  const blob = new Blob([zip([
    { name: '[Content_Types].xml', data: contentTypes },
    { name: '_rels/.rels', data: rels },
    { name: 'word/document.xml', data: documentXml(record) }
  ])], { type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = safeName(record)
  link.style.display = 'none'
  document.body.appendChild(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

export function printRecord(record: ExportRecord) {
  const rows = [['患者姓名', record.patientName], ['接诊编号', record.visitId], ['病历编号', record.recordId], ['版本', record.version], ['接诊医生', record.doctor], ['确认时间', record.confirmedAt]]
    .map(([label, value]) => `<div class="meta"><b>${xml(label)}</b><span>${xml(value)}</span></div>`).join('')
  let body = ''
  let section = ''
  for (const field of record.fields) {
    if (field.section && field.section !== section) { body += `<h2>${xml(field.section)}</h2>`; section = field.section }
    body += `<div class="field"><b>${xml(field.label)}</b><div>${xml(field.value).replace(/\r?\n/g, '<br>')}</div></div>`
  }
  const doc = `<!doctype html><html><head><meta charset="utf-8"><title>病历</title><style>@page{size:A4;margin:18mm}*{box-sizing:border-box}body{font-family:"Microsoft YaHei",sans-serif;color:#182b3a;font-size:14px;line-height:1.65;margin:0}h1{font-size:24px;text-align:center;margin:0 0 16px;border-bottom:2px solid #167c80;padding-bottom:10px}h2{font-size:16px;color:#167c80;border-bottom:1px solid #b8d5d7;margin:18px 0 8px;padding-bottom:3px}.meta{display:flex;border-bottom:1px solid #e3ecee;padding:4px 0}.meta b{width:82px;color:#58727c}.meta span{flex:1}.field{display:grid;grid-template-columns:130px 1fr;border-bottom:1px solid #e3ecee;padding:6px 0;min-height:32px}.field b{color:#58727c}footer{margin-top:24px;text-align:right;color:#7b9096;font-size:12px}</style></head><body><h1>病历记录</h1>${rows}${body}<footer>医疗工作台 · 病历打印件</footer></body></html>`
  const win = window.open('', '_blank')
  if (win) {
    win.document.open()
    win.document.write(doc)
    win.document.close()
    return
  }
  const frame = document.createElement('iframe')
  frame.style.cssText = 'position:fixed;width:0;height:0;border:0;right:0;bottom:0'
  document.body.appendChild(frame)
  frame.onload = () => {
    try { frame.contentWindow?.print() } finally { setTimeout(() => frame.remove(), 1000) }
  }
  frame.srcdoc = doc
}
