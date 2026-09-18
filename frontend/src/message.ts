export type MessageType = 'success' | 'error' | 'info'

export interface MessageDetail {
  text: string
  type: MessageType
}

/**
 * 通过窗口事件向应用根组件发送全局消息，避免各页面分别维护提示状态。
 */
export function showMessage(text: string, type: MessageType = 'info') {
  const message = text.trim()
  if (!message) return
  window.dispatchEvent(new CustomEvent<MessageDetail>('medicalai:message', {
    detail: { text: message, type }
  }))
}
