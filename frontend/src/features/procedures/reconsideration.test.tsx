// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The reconsideration of an approved case (ADR-043 step 5b): what staff read and the supervisor's form; what the applicant
// is told.
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { t } from '../../shared/i18n'
import { proceduresApi } from './api'
import type { CaseProcedure, Reconsideration, Stage } from './api'
import { ReconsiderationSection } from './Reconsideration'
import { StageProgress } from './StageProgress'

const texts = t('en')
const stage = (code: Stage['code']): Stage => ({
  code, rank: 10, requires: null, permission: 'procedure.stage.cadastral', labelEn: code, labelFr: code, status: 'COMPLETED', mayRecord: false, records: [],
})
const procedure = (over: Partial<CaseProcedure> = {}): CaseProcedure => ({
  id: 'a1', reference: 'DEMO-APP-2026-000001-X', state: 'APPROVED', typeCode: 'DEMO_IMMATRICULATION', officeCode: 'YDE1', procedure: 'A',
  stages: [stage('CADASTRAL_REVIEW'), stage('FIELD_VISIT'), stage('PUBLICATION')], gate: [], notices: [], objections: [],
  mayRecordObjection: false, version: 9, round: 0, reconsiderations: [], reconsiderProblem: null, ...over,
})
const opened = (over: Partial<Reconsideration> = {}): Reconsideration => ({
  id: 'r1', round: 1, reconsideredOutcome: 'APPROVED', objectionNumbers: ['DEMO-OBJ-2026-000007'], stages: ['CADASTRAL_REVIEW', 'PUBLICATION'],
  addedStages: [], justification: 'The neighbour\'s well was not before the decision authority', openedByName: 'Supervisor Two', openedBySelf: false,
  openedAt: '2026-11-14T10:00:00Z', roundOutcome: null, ...over,
})

describe('the reconsideration of an approved case (ADR-043 step 5b)', () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks() })

  it('lets a supervisor the server allows open it with a justification of at least 20 characters, adding stages', async () => {
    const saved = vi.fn()
    const reconsider = vi.spyOn(proceduresApi, 'reconsider').mockResolvedValue(procedure({ state: 'UNDER_REVIEW', round: 1,
      reconsiderations: [opened()], reconsiderProblem: 'CASE_NOT_APPROVED' }))
    render(<ReconsiderationSection texts={texts} lang="en" data={procedure()} onSaved={saved} />)
    fireEvent.click(screen.getByRole('button', { name: 'Open a reconsideration…' }))
    const confirm = screen.getByRole('button', { name: 'Open the reconsideration' }) as HTMLButtonElement
    expect(confirm.disabled).toBe(true)
    expect(screen.queryByRole('checkbox', { name: /Publication/ })).toBeNull()
    fireEvent.click(screen.getByRole('checkbox', { name: /Field visit/ }))
    fireEvent.change(screen.getByLabelText(/Justification/), { target: { value: 'Too short' } })
    expect(confirm.disabled).toBe(true)
    fireEvent.change(screen.getByLabelText(/Justification/), { target: { value: 'The neighbour\'s well was not before the decision authority' } })
    fireEvent.click(confirm)
    await waitFor(() => expect(reconsider).toHaveBeenCalledWith('a1', 9, ['FIELD_VISIT'], 'The neighbour\'s well was not before the decision authority'))
    expect(saved).toHaveBeenCalled()
  })

  it('shows every round: what it reconsiders, answers and reopens, why, and its outcome once decided', () => {
    render(<ReconsiderationSection texts={texts} lang="en" onSaved={vi.fn()} data={procedure({ state: 'APPROVED', round: 1,
      reconsiderProblem: 'GROUNDS_MISSING', reconsiderations: [opened({ addedStages: ['FIELD_VISIT'], roundOutcome: 'APPROVED' })] })} />)
    expect(screen.getByText('Answers the objections: DEMO-OBJ-2026-000007')).toBeTruthy()
    expect(screen.getByText(/Stages reopened: Cadastral review, Publication/)).toBeTruthy()
    expect(screen.getByText(/Added by the supervisor: Field visit/)).toBeTruthy()
    expect(screen.getByText('The neighbour\'s well was not before the decision authority')).toBeTruthy()
    expect(screen.getByText('New decision: Approved (simulated)')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Open a reconsideration…' })).toBeNull()
  })

  it('tells the supervisor who upheld the objection that another one reconsiders, and offers nothing', () => {
    const { container } = render(<ReconsiderationSection texts={texts} lang="en" onSaved={vi.fn()}
      data={procedure({ reconsiderProblem: 'OBJECTION_DECIDER_CANNOT_REOPEN' })} />)
    expect(screen.getByText('You upheld the objection: another supervisor reconsiders the case.')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Open a reconsideration…' })).toBeNull()
    cleanup()
    render(<ReconsiderationSection texts={texts} lang="en" onSaved={vi.fn()} data={procedure({ reconsiderProblem: 'PROCEDURE_FORBIDDEN' })} />)
    expect(container.textContent).toBe('')
    expect(screen.queryByText('Reconsideration')).toBeNull()
  })

  it('tells the applicant which stages are carried out again and that a new decision follows, never why', () => {
    render(<StageProgress texts={texts} lang="en" stages={[{ stage: 'CADASTRAL_REVIEW', status: 'OUTDATED', at: null }]}
      reconsiderations={[{ round: 1, stages: ['CADASTRAL_REVIEW', 'PUBLICATION'], openedAt: '2026-11-14T10:00:00Z', decided: false }]} />)
    expect(screen.getByText('Your case is reconsidered (round 1)')).toBeTruthy()
    expect(screen.getByText(/carries out again: Cadastral review, Publication and objections\. A new decision will follow/)).toBeTruthy()
    cleanup()
    render(<StageProgress texts={texts} lang="en" stages={[{ stage: 'CADASTRAL_REVIEW', status: 'COMPLETED', at: null }]}
      reconsiderations={[{ round: 1, stages: ['CADASTRAL_REVIEW'], openedAt: '2026-11-14T10:00:00Z', decided: true }]} />)
    expect(screen.queryByText('Your case is reconsidered (round 1)')).toBeNull()
  })

  it('names the new codes in both languages', () => {
    for (const lang of ['en', 'fr'] as const) {
      const errors = t(lang).errors as Record<string, string>
      for (const code of ['GROUNDS_MISSING', 'CREATION_IN_PROGRESS', 'EARLIER_DECIDER_CANNOT_DECIDE', 'RECONSIDERATION_OPENER_CANNOT_REVIEW',
        'OBJECTION_DECIDER_CANNOT_REOPEN', 'JUSTIFICATION_REQUIRED']) {
        expect(errors[code], `${lang} ${code}`).toBeTruthy()
      }
      expect((t(lang).procedures.gateReasons as Record<string, string>).RECONSIDERATION_PENDING).toBeTruthy()
    }
  })
})
