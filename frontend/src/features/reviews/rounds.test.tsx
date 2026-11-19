// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The review rounds of a reconsidered case (ADR-043 step 5b) in the staff's review record.
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { t } from '../../shared/i18n'
import type { Review } from './api'
import { ReviewRecord } from './ReviewRecord'

const texts = t('en')

describe('a reconsidered case in the review record', () => {
  afterEach(cleanup)

  it('shows staff the round and the earlier decision in the review record', () => {
    const review: Review = {
      id: 'a1', reference: 'DEMO-APP-2026-000001-X', state: 'UNDER_REVIEW', version: 10, officeCode: 'YDE1', assignments: [],
      steps: [{ kind: 'RECOMMENDATION', recommendation: 'RECOMMEND_APPROVAL', text: 'Round 0 finding', actorName: 'Reviewer', at: '2026-11-01T10:00:00Z', round: 0 }],
      decision: null, openAlerts: 0, youRecommended: false, procedureGate: [], round: 1,
      earlierDecisions: [{ outcome: 'APPROVED', grounds: 'First grounds', citedVersions: [], simulated: true, decidedByName: 'Decision One',
        at: '2026-11-02T10:00:00Z', round: 0 }],
    }
    render(<ReviewRecord texts={texts} lang="en" review={review} />)
    expect(screen.getByText('Reconsideration, round 1')).toBeTruthy()
    expect(screen.getByText(/Round 0: Approved \(simulated\), by Decision One/)).toBeTruthy()
    expect(screen.getByText('(round 0)')).toBeTruthy()
  })

})
