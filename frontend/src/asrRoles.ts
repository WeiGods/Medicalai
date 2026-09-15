import type { Utterance } from './types'

export function roleLabel(turn: Pick<Utterance, 'role' | 'speaker_id'>): string {
  if (turn.role === 'DOCTOR') return '医生'
  if (turn.role === 'PATIENT') return '患者'
  return turn.speaker_id == null ? '未识别角色' : `说话人 ${turn.speaker_id}`
}
