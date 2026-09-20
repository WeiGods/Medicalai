import {
  AlignmentType,
  BorderStyle,
  Document,
  Footer,
  Header,
  Packer,
  PageNumber,
  Paragraph,
  Table,
  TableCell,
  TableRow,
  TextRun,
  WidthType
} from 'docx'
import type { MedicalRecordContent } from './types'

export interface RecordExportData {
  visitNo: string
  versionNo: number
  confirmedAt: string | null
  content: MedicalRecordContent
}

export interface TemplateDefinition {
  version: number
  name: string
  description: string
  createPdf: (data: RecordExportData) => Promise<Blob>
  createDocx: (data: RecordExportData) => Promise<Blob>
}

const inky = '#1F2E2B'
const muted = '#667C72'
const line = '#D8E4DC'
const sectionBg = 'EAF2ED'

function text(value: string | null | undefined): string {
  return value ?? ''
}

function confirmedTime(value: string | null): string {
  if (!value) return ''
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function basicRows(content: MedicalRecordContent): Array<Array<[string, string]>> {
  return [
    [['患者姓名', text(content.name)], ['性别', text(content.gender)]],
    [['年龄', content.age == null ? '' : String(content.age)], ['联系方式', text(content.phone)]]
  ]
}

function basicFieldRows(content: MedicalRecordContent): Array<[string, string]> {
  return [
    ['患者姓名', text(content.name)],
    ['性别', text(content.gender)],
    ['年龄', content.age == null ? '' : String(content.age)],
    ['联系方式', text(content.phone)]
  ]
}

// ---------------------------------------------------------------------------
// Word 生成
// ---------------------------------------------------------------------------

const wordFont = { ascii: 'Microsoft YaHei', eastAsia: 'Microsoft YaHei', hAnsi: 'Microsoft YaHei' }

interface WordStyle {
  merged: boolean
  labelSize: number
  valueSize: number
  sectionSize: number
  cellMarginY: number
  line: number
}

function createWordDocument(data: RecordExportData, style: WordStyle): Document {
  const border = { style: BorderStyle.SINGLE, size: 4, color: line }
  const tableBorders = { top: border, bottom: border, left: border, right: border, insideHorizontal: border, insideVertical: border }

  const sectionTitle = (title: string, before: number) => new Paragraph({
    spacing: { before, after: 130, line: 340 },
    children: [new TextRun({ text: title, size: 24, bold: true, color: inky, font: wordFont })]
  })

  const basicRow = (row: Array<[string, string]>) => new TableRow({
    children: row.map(([label, value]) => new TableCell({
      width: { size: 50, type: WidthType.PERCENTAGE },
      margins: { top: style.cellMarginY, bottom: style.cellMarginY, left: 160, right: 160 },
      children: [new Paragraph({
        spacing: { line: style.line },
        children: [
          new TextRun({ text: `${label}：`, size: style.labelSize, bold: true, color: muted, font: wordFont }),
          new TextRun({ text: value, size: style.valueSize, color: inky, font: wordFont })
        ]
      })]
    }))
  })

  const fieldRow = ([label, content]: [string, string | null]) => new TableRow({
    children: [
      new TableCell({
        width: { size: 25, type: WidthType.PERCENTAGE },
        margins: { top: style.cellMarginY, bottom: style.cellMarginY, left: 160, right: 160 },
        children: [new Paragraph({
          spacing: { line: style.line },
          children: [new TextRun({ text: label, size: style.labelSize, bold: true, color: muted, font: wordFont })]
        })]
      }),
      new TableCell({
        width: { size: 75, type: WidthType.PERCENTAGE },
        margins: { top: style.cellMarginY, bottom: style.cellMarginY, left: 160, right: 160 },
        children: text(content).split(/\r?\n/).map(row => new Paragraph({
          spacing: { line: style.line },
          children: [new TextRun({ text: row || ' ', size: style.valueSize, color: inky, font: wordFont })]
        }))
      })
    ]
  })

  const sectionHeaderRow = (title: string) => new TableRow({
    children: [new TableCell({
      columnSpan: 2,
      shading: { fill: sectionBg },
      margins: { top: 80, bottom: 80, left: 160, right: 160 },
      children: [new Paragraph({
        spacing: { line: style.line },
        children: [new TextRun({ text: title, size: style.sectionSize, bold: true, color: inky, font: wordFont })]
      })]
    })]
  })

  const fieldTable = (rows: Array<[string, string | null]>) => new Table({
    width: { size: 100, type: WidthType.PERCENTAGE },
    columnWidths: [2400, 7200],
    borders: tableBorders,
    rows: rows.map(fieldRow)
  })

  const visitRows: Array<[string, string | null]> = [
    ['1. 患者主诉', data.content.chief],
    ['2. 患者现病史', data.content.present],
    ['3. 患者既往史', data.content.past]
  ]
  const treatmentRows: Array<[string, string | null]> = [
    ['1. 医生处理意见', data.content.opinion],
    ['2. 用药情况', data.content.medication],
    ['3. 复诊建议', data.content.followup]
  ]
  const signRows: Array<[string, string | null]> = [
    ['接诊医生', data.content.doctor],
    ['接诊日期', data.content.date],
    ['病历版本', `v${data.versionNo}`],
    ['确认时间', confirmedTime(data.confirmedAt)]
  ]

  let children: Array<Paragraph | Table>
  if (style.merged) {
    const rows = [
      sectionHeaderRow('一、基本信息'),
      ...basicRows(data.content).map(basicRow),
      sectionHeaderRow('二、就诊内容'),
      ...visitRows.map(fieldRow),
      sectionHeaderRow('三、诊疗记录'),
      ...treatmentRows.map(fieldRow),
      sectionHeaderRow('四、签署信息'),
      ...signRows.map(fieldRow)
    ]
    children = [new Table({
      width: { size: 100, type: WidthType.PERCENTAGE },
      columnWidths: [2400, 7200],
      borders: tableBorders,
      rows
    })]
  } else {
    children = [
      sectionTitle('一、基本信息', 0),
      new Table({
        width: { size: 100, type: WidthType.PERCENTAGE },
        columnWidths: [4800, 4800],
        borders: tableBorders,
        rows: basicRows(data.content).map(basicRow)
      }),
      sectionTitle('二、就诊内容', 260),
      fieldTable(visitRows),
      sectionTitle('三、诊疗记录', 220),
      fieldTable(treatmentRows),
      sectionTitle('四、签署信息', 220),
      fieldTable(signRows)
    ]
  }

  return new Document({
    styles: { default: { document: { run: { size: 21, color: inky, font: wordFont } } } },
    sections: [{
      properties: {
        page: {
          size: { width: 11906, height: 16838 },
          margin: { top: 1180, right: 1180, bottom: 1180, left: 1180 }
        }
      },
      headers: {
        default: new Header({ children: [new Paragraph({
          alignment: AlignmentType.CENTER,
          spacing: { after: 60, line: 340 },
          children: [new TextRun({ text: '门诊病历', size: 30, bold: true, color: inky, font: wordFont })]
        })] })
      },
      footers: {
        default: new Footer({ children: [new Paragraph({
          alignment: AlignmentType.CENTER,
          children: [
            new TextRun({ text: '第 ', size: 17, color: muted, font: wordFont }),
            new TextRun({ children: [PageNumber.CURRENT], size: 17, color: muted, font: wordFont }),
            new TextRun({ text: ' 页 / 共 ', size: 17, color: muted, font: wordFont }),
            new TextRun({ children: [PageNumber.TOTAL_PAGES], size: 17, color: muted, font: wordFont }),
            new TextRun({ text: ' 页', size: 17, color: muted, font: wordFont })
          ]
        })] })
      },
      children: [
        new Paragraph({
          alignment: AlignmentType.CENTER,
          spacing: { after: 240, line: 300 },
          children: [new TextRun({
            text: `接诊编号：${text(data.visitNo)}    病历版本：v${data.versionNo}    确认时间：${confirmedTime(data.confirmedAt)}`,
            size: 17, color: muted, font: wordFont
          })]
        }),
        ...children
      ]
    }]
  })
}

// ---------------------------------------------------------------------------
// PDF 生成
// ---------------------------------------------------------------------------

interface PdfStyle {
  merged: boolean
  fontSize: number
  lineHeight: number
  padding: number
  margins: [number, number, number, number]
  metadataGap: number
  labelSize: number
  valueSize: number
  sectionSize: number
}

async function createPdfDocument(data: RecordExportData, style: PdfStyle): Promise<Blob> {
  const pdfMake = await createPdfEngine()
  type PdfRow = Array<Record<string, unknown>>
  const tableLayout = {
    hLineColor: () => line,
    vLineColor: () => line,
    paddingLeft: () => 8,
    paddingRight: () => 8,
    paddingTop: () => style.padding,
    paddingBottom: () => style.padding
  }
  const fieldRows = (rows: Array<[string, string | null]>): PdfRow[] =>
    rows.map(([label, content]) => [
      { text: label, style: 'tableLabel' },
      { text: text(content) || ' ', style: 'tableValue' }
    ])
  const visitRows: Array<[string, string | null]> = [
    ['1. 患者主诉', data.content.chief],
    ['2. 患者现病史', data.content.present],
    ['3. 患者既往史', data.content.past]
  ]
  const treatmentRows: Array<[string, string | null]> = [
    ['1. 医生处理意见', data.content.opinion],
    ['2. 用药情况', data.content.medication],
    ['3. 复诊建议', data.content.followup]
  ]
  const signRows: Array<[string, string | null]> = [
    ['接诊医生', data.content.doctor],
    ['接诊日期', data.content.date],
    ['病历版本', `v${data.versionNo}`],
    ['确认时间', confirmedTime(data.confirmedAt)]
  ]
  const metadata = {
    text: `接诊编号：${text(data.visitNo)}    确认时间：${confirmedTime(data.confirmedAt)}`,
    style: 'metadata',
    margin: [0, 0, 0, style.metadataGap] as [number, number, number, number]
  }

  let content: Array<Record<string, unknown>>
  if (style.merged) {
    const sectionHeader = (title: string): PdfRow => [{ text: title, colSpan: 2, style: 'tableSectionHeader' }, {}]
    const body: PdfRow[] = [
      sectionHeader('一、基本信息'),
      ...fieldRows(basicFieldRows(data.content)),
      sectionHeader('二、就诊内容'),
      ...fieldRows(visitRows),
      sectionHeader('三、诊疗记录'),
      ...fieldRows(treatmentRows),
      sectionHeader('四、签署信息'),
      ...fieldRows(signRows)
    ]
    content = [
      metadata,
      { table: { widths: ['24%', '76%'], body }, layout: tableLayout }
    ]
  } else {
    const sectionTitle = (title: string) => ({
      text: title, style: 'sectionTitle', margin: [0, 0, 0, 8] as [number, number, number, number]
    })
    const basicBody: PdfRow[] = basicRows(data.content).map(row => row.map(([label, value]) => ({
      text: [
        { text: `${label}：`, style: 'tableLabel' },
        { text: value || ' ', style: 'tableValue' }
      ]
    })))
    const fieldTable = (rows: Array<[string, string | null]>) => ({
      table: { widths: ['24%', '76%'], body: fieldRows(rows) },
      layout: tableLayout,
      margin: [0, 0, 0, style.metadataGap] as [number, number, number, number]
    })
    content = [
      metadata,
      sectionTitle('一、基本信息'),
      { table: { widths: ['*', '*'], body: basicBody }, layout: tableLayout, margin: [0, 0, 0, style.metadataGap] },
      sectionTitle('二、就诊内容'),
      fieldTable(visitRows),
      sectionTitle('三、诊疗记录'),
      fieldTable(treatmentRows),
      sectionTitle('四、签署信息'),
      fieldTable(signRows)
    ]
  }

  const definition = {
    pageSize: 'A4',
    pageMargins: style.margins,
    defaultStyle: { font: 'NotoSansSC', fontSize: style.fontSize, lineHeight: style.lineHeight, color: inky },
    header: {
      columns: [
        { text: '门诊病历', style: 'documentTitle' },
        { text: `v${data.versionNo}`, style: 'headerVersion', alignment: 'right' }
      ],
      margin: [54, 34, 54, 0] as [number, number, number, number]
    },
    footer(currentPage: number, pageCount: number) {
      return { text: `第 ${currentPage} 页 / 共 ${pageCount} 页`, style: 'pageFooter' }
    },
    content,
    styles: {
      documentTitle: { font: 'NotoSansSC', fontSize: 17, bold: true, color: inky },
      headerVersion: { font: 'NotoSansSC', fontSize: 9, color: muted, margin: [0, 6, 0, 0] as [number, number, number, number] },
      metadata: { font: 'NotoSansSC', fontSize: 8.5, color: muted, lineHeight: 1.4 },
      sectionTitle: { font: 'NotoSansSC', fontSize: style.sectionSize, bold: true, color: inky },
      tableSectionHeader: { font: 'NotoSansSC', fontSize: style.sectionSize, bold: true, color: inky, background: '#EAF2ED' },
      tableLabel: { font: 'NotoSansSC', fontSize: style.labelSize, bold: true, color: muted },
      tableValue: { font: 'NotoSansSC', fontSize: style.valueSize, color: inky },
      pageFooter: { font: 'NotoSansSC', fontSize: 8, color: muted, alignment: 'center' }
    }
  }
  const blob = await pdfMake.createPdf(definition).getBlob()
  return new Blob([blob], { type: 'application/pdf' })
}

async function loadNotoSansFont(): Promise<Uint8Array> {
  const response = await fetch('/NotoSansSC-Regular.ttf')
  if (!response.ok) throw new Error('中文字体加载失败')
  return new Uint8Array(await response.arrayBuffer())
}

async function createPdfEngine() {
  const [pdfmakeModule, vfsModule, fontData] = await Promise.all([
    import('pdfmake/build/pdfmake'),
    import('pdfmake/build/vfs_fonts'),
    loadNotoSansFont()
  ])
  const pdfMake = (pdfmakeModule as unknown as { default: any }).default
  const virtualfs = pdfMake.virtualfs
  if (virtualfs?.writeFileSync) {
    virtualfs.writeFileSync('NotoSansSC-Regular.ttf', fontData)
  }
  if (typeof vfsModule === 'object' && vfsModule && 'default' in vfsModule) {
    const vfs = (vfsModule as { default?: Record<string, string> }).default
    if (vfs && pdfMake.addVirtualFileSystem) pdfMake.addVirtualFileSystem(vfs)
  }
  pdfMake.setFonts({
    NotoSansSC: {
      normal: 'NotoSansSC-Regular.ttf',
      bold: 'NotoSansSC-Regular.ttf',
      italics: 'NotoSansSC-Regular.ttf',
      boldItalics: 'NotoSansSC-Regular.ttf'
    }
  })
  return pdfMake
}

// ---------------------------------------------------------------------------
// 模板注册表
// ---------------------------------------------------------------------------

export const TEMPLATES: TemplateDefinition[] = [
  {
    version: 6,
    name: '紧凑单页',
    description: '合并表格、小字号，正常病历单页呈现',
    createPdf: data => createPdfDocument(data, {
      merged: true, fontSize: 9.5, lineHeight: 1.35, padding: 4,
      margins: [54, 60, 54, 50], metadataGap: 12,
      labelSize: 9, valueSize: 9.5, sectionSize: 10
    }),
    createDocx: data => Packer.toBlob(createWordDocument(data, {
      merged: true, labelSize: 19, valueSize: 20, sectionSize: 20, cellMarginY: 90, line: 300
    }))
  },
  {
    version: 7,
    name: '标准病历',
    description: '独立节标题与分节表格，经典文档层次',
    createPdf: data => createPdfDocument(data, {
      merged: false, fontSize: 10.5, lineHeight: 1.55, padding: 7,
      margins: [54, 72, 54, 62], metadataGap: 16,
      labelSize: 9.5, valueSize: 10, sectionSize: 13
    }),
    createDocx: data => Packer.toBlob(createWordDocument(data, {
      merged: false, labelSize: 19, valueSize: 21, sectionSize: 24, cellMarginY: 110, line: 330
    }))
  },
  {
    version: 8,
    name: '宽松阅读',
    description: '字号与行距更大，可读性优先，长内容自动分页',
    createPdf: data => createPdfDocument(data, {
      merged: true, fontSize: 11, lineHeight: 1.6, padding: 8,
      margins: [58, 66, 58, 58], metadataGap: 14,
      labelSize: 10.5, valueSize: 11, sectionSize: 11.5
    }),
    createDocx: data => Packer.toBlob(createWordDocument(data, {
      merged: true, labelSize: 21, valueSize: 22, sectionSize: 22, cellMarginY: 130, line: 360
    }))
  }
]

export const DEFAULT_TEMPLATE_VERSION = TEMPLATES[0].version

export function getTemplate(version: number): TemplateDefinition {
  return TEMPLATES.find(t => t.version === version) ?? TEMPLATES[0]
}

export async function createTemplatePdfBlob(version: number, data: RecordExportData): Promise<Blob> {
  return getTemplate(version).createPdf(data)
}

export async function createTemplateDocxBlob(version: number, data: RecordExportData): Promise<Blob> {
  return getTemplate(version).createDocx(data)
}
