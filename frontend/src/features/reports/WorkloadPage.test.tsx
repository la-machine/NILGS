// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { t } from '../../shared/i18n'
import type { PageContext, Session } from '../../shared/session/types'
import { reportsApi } from './api'
import type { OpenCase, WorkloadSummary } from './api'
import { WorkloadPage } from './WorkloadPage'

const summary: WorkloadSummary = {
  office: 'YDE1', asOf: '2026-10-27T12:00:00Z', from: '2026-07-29T12:00:00Z',
  targets: [
    { state: 'COMPLETENESS_REVIEW', seconds: 432_000 }, { state: 'INFO_REQUESTED', seconds: 2_592_000 },
    { state: 'UNDER_REVIEW', seconds: 864_000 }, { state: 'PENDING_DECISION', seconds: 432_000 },
  ],
  counts: [{ state: 'COMPLETENESS_REVIEW', cases: 2 }, { state: 'UNDER_REVIEW', cases: 1 }, { state: 'APPROVED', cases: 4 }],
  open: [{ state: 'COMPLETENESS_REVIEW', open: 2, overdue: 1 }, { state: 'UNDER_REVIEW', open: 1, overdue: 0 }],
  assignees: [{ name: 'Rita Reviewer', state: 'UNDER_REVIEW', cases: 1, overdue: 0 }],
  durations: [{ state: 'COMPLETENESS_REVIEW', stays: 5, averageSeconds: 180_000, medianSeconds: 172_800, longestSeconds: 540_000, overTarget: 1 }],
}

const cases: OpenCase[] = [
  { id: 'c1', reference: 'DEMO-APP-2026-000001-X', parcelReference: 'DEMO-PCL-YDE1-2026-000001-7', state: 'COMPLETENESS_REVIEW',
    since: '2026-10-20T09:00:00Z', waitedSeconds: 615_600, targetSeconds: 432_000, overdue: true, assigneeName: null },
  { id: 'c2', reference: 'DEMO-APP-2026-000002-Y', parcelReference: 'DEMO-PCL-YDE1-2026-000002-5', state: 'UNDER_REVIEW',
    since: '2026-10-26T09:00:00Z', waitedSeconds: 97_200, targetSeconds: 864_000, overdue: false, assigneeName: 'Rita Reviewer' },
]

function context(permissions: string[]): PageContext {
  const session: Session = {
    userId: 'u1', displayName: 'Sam Supervisor', accountType: 'STAFF', roles: ['SUPERVISOR'], grants: [{ role: 'SUPERVISOR', officeCode: 'YDE1' }],
    permissions, specialGrants: [], authLevel: 'FULL', nextStep: 'DONE', identityVerified: true, authenticatedAt: '2026-10-27T08:00:00Z',
  }
  return { texts: t('en'), lang: 'en', session, run: async (a) => { await a() }, refreshSession: async () => {} }
}

describe('the workload of an office, for its supervisor', () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks() })

  it('shows each stage against its target, the counts, the load per officer and the stage durations', async () => {
    vi.spyOn(reportsApi, 'workload').mockResolvedValue(summary)
    vi.spyOn(reportsApi, 'cases').mockResolvedValue(cases)
    render(<WorkloadPage ctx={context(['report.workload@YDE1', 'application.read@YDE1'])} params={new URLSearchParams()} />)
    const completeness = (await screen.findByText('1 overdue')).closest('li') as HTMLElement
    expect(completeness.dataset.stage).toBe('COMPLETENESS_REVIEW')
    expect(completeness.className).toContain('late')
    expect(within(completeness).getByText('1 overdue')).toBeTruthy()
    expect(within(completeness).getByText('target: 5 d')).toBeTruthy()
    expect(screen.getAllByText('Rita Reviewer').length).toBeGreaterThan(0)
    expect(screen.getByText('2 d 2 h')).toBeTruthy()
    expect(reportsApi.workload).toHaveBeenCalledWith('YDE1')
  })

  it('lists the cases under way, longest waiting first, flags the overdue ones, and opens a case for its readers', async () => {
    vi.spyOn(reportsApi, 'workload').mockResolvedValue(summary)
    vi.spyOn(reportsApi, 'cases').mockResolvedValue(cases)
    render(<WorkloadPage ctx={context(['report.workload@YDE1', 'application.read@YDE1'])} params={new URLSearchParams('state=COMPLETENESS_REVIEW&overdue=1')} />)
    await waitFor(() => expect(reportsApi.cases).toHaveBeenCalledWith('YDE1', 'COMPLETENESS_REVIEW', true, 0))
    const late = (await screen.findByText('DEMO-APP-2026-000001-X')).closest('tr') as HTMLElement
    expect(within(late).getByText('Overdue')).toBeTruthy()
    expect(within(late).getByText('Not claimed')).toBeTruthy()
    expect(within(late).getByText('7 d 3 h')).toBeTruthy()
    expect(screen.getByRole('link', { name: 'DEMO-APP-2026-000001-X' }).getAttribute('href')).toBe('#/cases/c1')
  })

  it('shows references without a link to someone who reads the report but not the cases', async () => {
    vi.spyOn(reportsApi, 'workload').mockResolvedValue(summary)
    vi.spyOn(reportsApi, 'cases').mockResolvedValue(cases)
    render(<WorkloadPage ctx={context(['report.workload@YDE1'])} params={new URLSearchParams()} />)
    await screen.findByText('DEMO-APP-2026-000001-X')
    expect(screen.queryByRole('link', { name: 'DEMO-APP-2026-000001-X' })).toBeNull()
  })
})
