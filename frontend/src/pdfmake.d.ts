declare module 'pdfmake/build/pdfmake' {
  const pdfmake: any
  export default pdfmake
}

declare module 'pdfmake/build/vfs_fonts' {
  const vfs: Record<string, string>
  export default vfs
}
