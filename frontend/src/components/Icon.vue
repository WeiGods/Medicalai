<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ name: string; label?: string }>()

const icons: Record<string, string> = {
  pulse: '<path d="M3 12h5l3-8 3 16 3-8h4"/>',
  users: '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2m20 0v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75"/><circle cx="9" cy="7" r="4"/>',
  upload: '<path d="M12 16V3m-5 5 5-5 5 5M4 16v4a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-4"/>',
  mic: '<rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 10v2a7 7 0 0 0 14 0v-2m-7 9v3m-4 0h8"/>',
  text: '<path d="M4 4h16v13H9l-5 4V4Zm4 4h8M8 12h5"/>',
  file: '<path d="M14 2H5v20h14V7l-5-5Zm0 0v6h5M8 12h8M8 16h8"/>',
  check: '<path d="m5 12 4 4L19 6"/>',
  shield: '<path d="m12 2 8 4v6c0 5-8 10-8 10S4 17 4 12V6l8-4Z"/><path d="m8 12 3 3 5-6"/>',
  download: '<path d="M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5"/>',
  chevron: '<path d="m9 5 7 7-7 7"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  calendar: '<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M16 3v4M8 3v4M3 11h18m-13 4h2m4 0h2"/>',
  gear: '<path d="m9 3-1 3-3 1-2 4 2 2v4l4 3 3-1 3 1 4-3v-4l2-2-2-4-3-1-1-3H9Z"/><circle cx="12" cy="12" r="3"/>',
  help: '<circle cx="12" cy="12" r="9"/><path d="M9.5 9a2.5 2.5 0 1 1 4.2 1.8c-1 .7-1.7 1.1-1.7 2.7m0 3h.01"/>',
  id: '<rect x="2" y="5" width="20" height="14" rx="2"/><circle cx="8" cy="10" r="2"/><path d="M5 16c0-4 6-4 6 0m3-7h5m-5 4h4"/>',
  phone: '<path d="m6 3 3 4-2 3a15 15 0 0 0 7 7l3-2 4 3-1 3C11 24 0 13 3 4l3-1Z"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  stop: '<rect x="6" y="6" width="12" height="12" rx="2"/>',
  x: '<path d="m6 6 12 12M6 18 18 6"/>',
  play: '<path d="m8 5 11 7-11 7V5Z"/>',
  pause: '<path d="M8 5v14M16 5v14"/>',
  sparkle: '<path d="m12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5L12 3Zm7 0v4m-2-2h4"/>',
  trash: '<path d="M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2m3 0v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m5 5v6m4-6v6"/>',
  refresh: '<path d="M3 10a9 9 0 0 1 15-6l3 3M21 2v5h-5m5 7a9 9 0 0 1-15 6l-3-3m0 5v-5h5"/>',
  save: '<path d="m17 3 4 4v14H3V3h14ZM7 3v6h10V3M7 21v-8h10v8"/>',
  edit: '<path d="m16 3 5 5-12 12-6 1 1-6L16 3ZM13 6 18 11"/>',
  lock: '<rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V6a4 4 0 0 1 8 0v4m-4 4v3"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6m0-10h.01"/>',
  copy: '<rect x="8" y="8" width="13" height="13" rx="2"/><path d="M16 8V3H3v13h5"/>',
  list: '<path d="M9 5h12M9 12h12M9 19h12M3 5h1m-1 7h1m-1 7h1"/>',
  arrow: '<path d="M4 12h16m-6-6 6 6-6 6"/>',
  building: '<path d="M5 21V3h14v18M3 21h18M9 7h1m4 0h1m-6 4h1m4 0h1m-5 10v-5h4v5"/>',
  print: '<path d="M6 9V2h12v7M6 18H3V9h18v9h-3M6 14h12v8H6v-8Zm11-2h1"/>',
  search: '<circle cx="10.8" cy="10.8" r="6.8"/><path d="m16 16 5 5"/>',
  bell: '<path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9ZM10 21h4"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>',
  home: '<path d="m3 11 9-8 9 8v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-9Z"/><path d="M9 21v-6h6v6"/>',
  warning: '<path d="m12 3 10 18H2L12 3Z"/><path d="M12 9v5m0 3h.01"/>',
  menu: '<path d="M4 6h16M4 12h16M4 18h16"/>',
  more: '<circle cx="5" cy="12" r="1" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1" fill="currentColor" stroke="none"/><circle cx="19" cy="12" r="1" fill="currentColor" stroke="none"/>',
  eye: '<path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/>'
}

const markup = computed(() => icons[props.name] || icons.file)
</script>

<template>
  <svg viewBox="0 0 24 24" :aria-label="props.label" :aria-hidden="props.label ? undefined : 'true'" focusable="false" v-html="markup"></svg>
</template>
