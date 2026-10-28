// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { AUDIT_ACTION_CODES } from './actions'

describe('audit action suggestions', () => {
  it('are exactly the actions the server records', () => {
    const source = readFileSync(resolve(__dirname, '../../../../backend/src/main/java/org/snigf/mvp/audit/AuditAction.java'), 'utf8')
    const body = source.slice(source.indexOf('enum AuditAction'), source.indexOf(';', source.indexOf('enum AuditAction')))
    const server = [...body.matchAll(/^\s+([A-Z][A-Z_]+)\b/gm)].map((m) => m[1])
    expect(server.length).toBeGreaterThan(20)
    expect([...AUDIT_ACTION_CODES].sort()).toEqual([...server].sort())
  })
})
