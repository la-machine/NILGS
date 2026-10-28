// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { t } from '../../shared/i18n'
import type { Session } from '../../shared/session/types'
import { DialogProvider } from '../../shared/ui/Dialog'
import { reviewsApi } from './api'
import type { Review } from './api'
import { ReviewPanel } from './ReviewPanel'
import { ReviewRecord } from './ReviewRecord'

const pending: Review = {
  id: 'a1', reference: 'DEMO-APP-2026-000001-X', state: 'PENDING_DECISION', version: 7, officeCode: 'YDE1',
  assignments: [
    { stage: 'REVIEW', assigneeName: 'Reviewer', at: '2026-10-24T10:00:00Z', mine: false },
    { stage: 'DECISION', assigneeName: 'Decider', at: '2026-10-24T11:00:00Z', mine: true },
  ],
  steps: [{ kind: 'RECOMMENDATION', recommendation: 'RECOMMEND_APPROVAL', text: 'Deed and plan match the register', actorName: 'Reviewer',
    at: '2026-10-24T10:30:00Z' }],
  decision: null, openAlerts: 0, youRecommended: false,
}

const citable = [{ id: 'v1', label: 'Proof of right (DEMO) — deed.pdf (v1)' }]

function session(permissions: string[]): Session {
  return {
    userId: 'u1', displayName: 'Decider', accountType: 'STAFF', roles: ['DECISION_AUTHORITY'], grants: [{ role: 'DECISION_AUTHORITY', officeCode: 'YDE1' }],
    permissions, specialGrants: [], authLevel: 'FULL', nextStep: 'DONE', identityVerified: true, authenticatedAt: '2026-10-24T08:00:00Z',
  }
}

const authority = ['application.read#assigned', 'decision.record#assigned']

function panel(review: Review, permissions = authority, changed = vi.fn()) {
  return render(
    <DialogProvider>
      <ReviewPanel texts={t('en')} session={session(permissions)} review={review} citable={citable} onChanged={changed} onReload={() => {}} />
    </DialogProvider>,
  )
}

describe('the decision authority\'s panel', () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks() })

  it('shows the simulated notice, then records the decision with grounds and citations after confirmation', async () => {
    const decide = vi.spyOn(reviewsApi, 'decide').mockResolvedValue({ ...pending, state: 'APPROVED', version: 8 })
    const changed = vi.fn()
    panel(pending, authority, changed)
    expect(screen.getByRole('note').textContent).toBe('Simulated decision — no legal effect')
    fireEvent.click(screen.getByLabelText('Approved (simulated)'))
    fireEvent.change(screen.getByLabelText(/^Grounds/), { target: { value: ' The right is proven by the deed ' } })
    fireEvent.click(screen.getByLabelText(citable[0].label))
    fireEvent.click(screen.getByRole('button', { name: 'Record the simulated decision' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Record the simulated decision' }))
    await waitFor(() => expect(decide).toHaveBeenCalledWith('a1',
      { expectedVersion: 7, outcome: 'APPROVED', grounds: 'The right is proven by the deed', citedVersions: ['v1'] }))
    expect(changed).toHaveBeenCalledTimes(1)
  })

  it('asks for a citation and sends nothing without one', async () => {
    const decide = vi.spyOn(reviewsApi, 'decide')
    panel(pending)
    fireEvent.click(screen.getByLabelText('Refused (simulated)'))
    fireEvent.change(screen.getByLabelText(/^Grounds/), { target: { value: 'The plan contradicts the register' } })
    fireEvent.click(screen.getByRole('button', { name: 'Record the simulated decision' }))
    expect(screen.getByText('Cite at least one document.')).toBeTruthy()
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(decide).not.toHaveBeenCalled()
  })

  it('blocks the decision while an overlap alert is open on the parcel', () => {
    panel({ ...pending, openAlerts: 1 })
    expect(screen.getByText(/1 overlap or duplicate alert\(s\) open on the parcel/)).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Record the simulated decision' })).toHaveProperty('disabled', true)
  })

  it('offers nothing but the separation-of-duties notice to whoever recommended', () => {
    panel({ ...pending, assignments: [pending.assignments[0]], youRecommended: true })
    expect(screen.getByText('You cannot decide this case')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Claim this case' })).toBeNull()
  })

  it('lets a reviewer claim a case waiting for review', async () => {
    const claim = vi.spyOn(reviewsApi, 'claim').mockResolvedValue(pending)
    panel({ ...pending, state: 'UNDER_REVIEW', assignments: [], steps: [] }, ['application.read#assigned', 'review.recommend#assigned'])
    fireEvent.click(screen.getByRole('button', { name: 'Claim this case' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Claim this case' }))
    await waitFor(() => expect(claim).toHaveBeenCalledWith('a1', 'REVIEW'))
  })
})

describe('a recorded decision', () => {
  afterEach(cleanup)

  it('always carries the simulated notice and names the documents cited', () => {
    render(<ReviewRecord texts={t('en')} lang="en" citedNames={{ v1: 'deed.pdf' }} review={{ ...pending, state: 'APPROVED',
      decision: { outcome: 'APPROVED', grounds: 'The right is proven', citedVersions: ['v1'], simulated: true, decidedByName: 'Decider',
        at: '2026-10-24T12:00:00Z' } }} />)
    expect(screen.getByRole('note').textContent).toBe('Simulated decision — no legal effect')
    expect(screen.getByText('Approved (simulated)')).toBeTruthy()
    expect(screen.getByText('Documents cited: deed.pdf.')).toBeTruthy()
  })
})
