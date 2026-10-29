// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client'
import { t } from '../../shared/i18n'
import type { PageContext, Session } from '../../shared/session/types'
import { auditApi } from './api'
import type { AuditEvent } from './api'
import { AuditTimelinePage } from './AuditTimelinePage'

const ctx = (permissions: string[]): PageContext => {
  const session: Session = {
    userId: 's1', displayName: 'Supervisor', accountType: 'STAFF', roles: ['SUPERVISOR'], grants: [{ role: 'SUPERVISOR', officeCode: 'YDE1' }],
    permissions, specialGrants: [], authLevel: 'FULL', nextStep: 'DONE', identityVerified: true, authenticatedAt: '2026-10-28T08:00:00Z',
  }
  return { texts: t('en'), lang: 'en', session, run: async (a) => { await a() }, refreshSession: async () => {} }
}

const event = (pos: number, action: string, extra: Partial<AuditEvent> = {}): AuditEvent => ({
  chainPos: pos, id: `e${pos}`, occurredAt: '2026-10-28T09:00:00Z', actorType: 'USER', actorId: 'u1', actorLabel: null,
  actorRoles: ['INTAKE_OFFICER'], action, entityType: 'APPLICATION', entityId: 'a1', outcome: 'SUCCESS', reason: null,
  sourceIp: null, details: {}, actorName: 'Ines Intake', subjects: [{ type: 'APPLICATION', id: 'a1', officeCode: 'YDE1' }],
  intact: true, ...extra,
})

describe('the audit timeline of a case', () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks() })

  it('lists the events oldest first, in words, with who acted and the office, and says the supervisor reads their offices only', async () => {
    vi.spyOn(auditApi, 'timeline').mockResolvedValue({
      subjectType: 'APPLICATION', subjectId: 'a1', reference: 'DEMO-APP-2026-000001-X', truncated: false,
      events: [event(10, 'APPLICATION_SUBMITTED'), event(12, 'COMPLETENESS_ACCEPTED'),
        event(14, 'PAYMENT_CALLBACK_ACCEPTED', { actorType: 'SERVICE', actorId: null, actorLabel: 'payment-callback', actorRoles: [], actorName: null })],
    })
    render(<AuditTimelinePage ctx={ctx(['audit.event.search@YDE1'])}
      params={new URLSearchParams({ type: 'APPLICATION', reference: 'DEMO-APP-2026-000001-X' })} />)
    expect(await screen.findByText('Timeline of DEMO-APP-2026-000001-X')).toBeTruthy()
    expect(auditApi.timeline).toHaveBeenCalledWith('APPLICATION', 'DEMO-APP-2026-000001-X')
    const entries = Array.from(document.querySelectorAll('li[data-action]'))
    expect(entries.map((e) => e.getAttribute('data-action'))).toEqual(['APPLICATION_SUBMITTED', 'COMPLETENESS_ACCEPTED', 'PAYMENT_CALLBACK_ACCEPTED'])
    expect(screen.getByText('Accepted as complete')).toBeTruthy()
    expect(screen.getAllByText(/^Ines Intake \(INTAKE_OFFICER\) · YDE1/).length).toBe(2)
    expect(screen.getByText(/^Service payment-callback/)).toBeTruthy()
    expect(screen.getByText('Every event intact')).toBeTruthy()
    expect(screen.getByText(/You read the events of your offices only/)).toBeTruthy()
  })

  it('shows an altered event, and nothing for a record the reader may not read', async () => {
    vi.spyOn(auditApi, 'timeline').mockResolvedValue({
      subjectType: 'APPLICATION', subjectId: 'a1', reference: 'DEMO-APP-2026-000001-X', truncated: false,
      events: [event(10, 'APPLICATION_SUBMITTED'), event(12, 'COMPLETENESS_ACCEPTED', { intact: false })],
    })
    const { unmount } = render(<AuditTimelinePage ctx={ctx(['audit.event.search'])}
      params={new URLSearchParams({ type: 'APPLICATION', reference: 'DEMO-APP-2026-000001-X' })} />)
    expect(await screen.findByText('1 altered event(s)')).toBeTruthy()
    expect(screen.getByText('altered')).toBeTruthy()
    expect(screen.queryByText(/You read the events of your offices only/)).toBeNull()
    unmount()
    vi.spyOn(auditApi, 'timeline').mockRejectedValue(new ApiError(404, { code: 'TIMELINE_NOT_FOUND' }))
    render(<AuditTimelinePage ctx={ctx(['audit.event.search@YDE1'])}
      params={new URLSearchParams({ type: 'APPLICATION', reference: 'DEMO-APP-2026-999999-0' })} />)
    expect(await screen.findByText('Nothing to show')).toBeTruthy()
  })
})
