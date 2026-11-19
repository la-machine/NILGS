// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Helpers shared by the procedure screens (ADR-042): the tone of a stage's status, people as "name, capacity" lines,
// and a readable reason for each problem of the gate (the codes of V32 and V33).
import { fill } from '../../shared/i18n'
import type { Texts } from '../../shared/i18n'
import type { Person } from './api'

export const STATUS_TONE = {
  NOT_STARTED: 'neutral', COMPLETED: 'success', NOT_SATISFIED: 'warning', IN_PROGRESS: 'info', OUTDATED: 'warning',
} as const

/** "name, capacity" per line; lines without a comma are refused by the caller. */
export function people(text: string): Person[] | null {
  const lines = text.split('\n').map((l) => l.trim()).filter(Boolean)
  const out: Person[] = []
  for (const line of lines) {
    const i = line.indexOf(',')
    if (i < 1 || i === line.length - 1) return null
    out.push({ name: line.slice(0, i).trim(), capacity: line.slice(i + 1).trim() })
  }
  return out.length > 0 && out.length <= 12 ? out : null
}

/** A readable reason for one gate problem (the codes of V32 and V33). */
export function gateReason(texts: Texts, problem: string): string {
  const k = texts.procedures
  const [code, stage] = problem.split(':')
  const reasons = k.gateReasons as Record<string, string>
  const stageName = stage ? ((k.stageNames as Record<string, string>)[stage] ?? stage) : ''
  return reasons[code] ? fill(reasons[code], { stage: stageName }) : problem
}
