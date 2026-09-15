const formatter = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'
})

const apiDateTimePattern = /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/

/** Display instants in Beijing time, independent of the browser's timezone. */
export function formatDateTime(value: string | Date | null | undefined): string {
  if (!value) return ''
  if (typeof value === 'string' && apiDateTimePattern.test(value)) return value
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const parts = Object.fromEntries(formatter.formatToParts(date).map(p => [p.type, p.value]))
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`
}
