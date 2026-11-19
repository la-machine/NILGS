// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { t } from '../../shared/i18n'
import type { PageContext, Session } from '../../shared/session/types'
import { proceduresApi } from './api'
import type { CaseProcedure, Notice, Objection, Stage, StageRecord } from './api'
import { ObjectionQueuePage } from './ObjectionQueuePage'
import { ProcedurePanel } from './ProcedurePanel'
import { people } from './stages'
import { ProcedureQueuePage } from './ProcedureQueuePage'
import { PublicNoticesPage } from './PublicNoticesPage'
import { StageProgress } from './StageProgress'

const texts = t('en')
const session = { userId: 'me', displayName: 'Me', accountType: 'STAFF', permissions: ['procedure.stage.cadastral@YDE1'], specialGrants: [], roles: [], grants: [] } as unknown as Session
const ctx = { texts, lang: 'en', session, run: async (a: () => Promise<void>) => { await a() }, refreshSession: vi.fn() } as unknown as PageContext

const rec = (over: Partial<StageRecord> = {}): StageRecord => ({
  id: 'r1', outcome: 'COMPLETED', finding: 'The parcel matches the cadastre', details: {}, evidenceVersions: [], supersedes: null,
  correctionReason: null, recordedByName: 'Cadastre One', recordedBySelf: false, recordedAt: '2026-11-11T10:00:00Z', files: [], ...over,
})
const stage = (code: Stage['code'], over: Partial<Stage> = {}): Stage => ({
  code, rank: 10, requires: null, permission: 'procedure.stage.cadastral', labelEn: texts.procedures.stageNames[code], labelFr: code,
  status: 'NOT_STARTED', mayRecord: false, records: [], ...over,
})
const procedure = (over: Partial<CaseProcedure> = {}): CaseProcedure => ({
  id: 'a1', reference: 'DEMO-APP-2026-000001-X', state: 'UNDER_REVIEW', typeCode: 'DEMO_IMMATRICULATION', officeCode: 'YDE1', procedure: 'A',
  stages: [stage('CADASTRAL_REVIEW', { mayRecord: true }), stage('FIELD_VISIT'), stage('COMMISSION_REPORT'), stage('PUBLICATION')],
  gate: ['STAGE_MISSING:CADASTRAL_REVIEW', 'STAGE_MISSING:PUBLICATION'], notices: [], objections: [], mayRecordObjection: false,
  version: 4, round: 0, reconsiderations: [], reconsiderProblem: 'PROCEDURE_FORBIDDEN', ...over,
})
const notice = (over: Partial<Notice> = {}): Notice => ({
  id: 'n1', number: 'DEMO-PUB-2026-000001', parcelReference: 'DEMO-PCL-YDE1-2026-000001-Z', locality: 'Nkolbisson', period: '00:05:00',
  opensAt: '2026-11-13T10:00:00Z', closesAt: '2026-11-13T10:05:00Z', replaces: null, current: true, periodOpen: true, ...over,
})
const objection = (over: Partial<Objection> = {}): Objection => ({
  id: 'o1', number: 'DEMO-OBJ-2026-000001', noticeNumber: 'DEMO-PUB-2026-000001',
  objector: { name: 'DEMO Neighbour Abena', interest: 'NEIGHBOUR', identityVisible: false },
  statement: 'The eastern boundary crosses my field', receivedAt: '2026-11-13T10:02:00Z', late: false, recordedByName: 'Intake One',
  recordedBySelf: false, decision: null, mayDecide: false, files: [], ...over,
})
const published = (over: Partial<CaseProcedure> = {}) => procedure({
  stages: [stage('CADASTRAL_REVIEW', { status: 'COMPLETED' }), stage('FIELD_VISIT', { status: 'COMPLETED' }),
    stage('COMMISSION_REPORT', { status: 'COMPLETED' }), stage('PUBLICATION', { status: 'IN_PROGRESS', records: [rec({ id: 'p1' })] })],
  gate: ['OBJECTION_PERIOD_OPEN'], notices: [notice()], ...over,
})

describe('procedures', () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks() })

  it('lists the stages and what stops an approval, and records a stage', async () => {
    vi.spyOn(proceduresApi, 'read').mockResolvedValue(procedure())
    const record = vi.spyOn(proceduresApi, 'record').mockResolvedValue(procedure({
      stages: [stage('CADASTRAL_REVIEW', { status: 'COMPLETED', records: [rec({ recordedBySelf: true })] }), stage('FIELD_VISIT'),
        stage('COMMISSION_REPORT'), stage('PUBLICATION')],
      gate: ['STAGE_MISSING:PUBLICATION'],
    }))
    render(<ProcedurePanel texts={texts} lang="en" applicationId="a1" />)
    expect(await screen.findByText('Procedure A, direct first registration (DEMO)')).toBeTruthy()
    expect(screen.getByText('Cadastral review: not recorded yet')).toBeTruthy()
    expect(screen.getByText('A refusal may still be recommended and decided.')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Record this stage' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save the record' }))
    expect(await screen.findByText('Write at least 10 characters.')).toBeTruthy()
    expect(record).not.toHaveBeenCalled()
    fireEvent.change(screen.getByLabelText('Finding'), { target: { value: 'The parcel matches the cadastre' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save the record' }))
    await waitFor(() => expect(record).toHaveBeenCalledWith('a1', 'CADASTRAL_REVIEW',
      { outcome: 'COMPLETED', finding: 'The parcel matches the cadastre', details: {} }, []))
    expect(await screen.findByText('By you')).toBeTruthy()
  })

  it('records the duty receipt of the case\'s deposit for procedure B, never an amount (ADR-042 step 6, D24)', async () => {
    const b = procedure({ typeCode: 'DEMO_MUTATION', procedure: 'B',
      stages: [stage('TITLE_VERIFICATION', { status: 'COMPLETED', records: [rec({ details: { titleNumber: 'DEMO-TF-YDE1-000003-D', versionNo: 2,
        holders: [{ name: 'DEMO Seller', transferor: 'p2' }] } })] }), stage('DUTIES_EVIDENCE', { mayRecord: true })],
      gate: ['STAGE_MISSING:DUTIES_EVIDENCE'],
      caseDeposit: { id: 'd1', status: 'HOLDING', channel: 'PROFESSIONAL_ACCOUNT', depositedAt: '2026-11-16T09:00:00Z', titleNumber: 'DEMO-TF-YDE1-000003-D',
        receipts: [{ id: 'q1', name: 'receipt.pdf' }] } })
    vi.spyOn(proceduresApi, 'read').mockResolvedValue(b)
    const record = vi.spyOn(proceduresApi, 'record').mockResolvedValue(b)
    render(<ProcedurePanel texts={texts} lang="en" applicationId="a1" />)
    expect(await screen.findByText('Title DEMO-TF-YDE1-000003-D, version 2: DEMO Seller → p2')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Record this stage' }))
    expect(screen.getByText("The case's deposit on title DEMO-TF-YDE1-000003-D: HOLDING")).toBeTruthy()
    expect(screen.queryByLabelText(/amount/i)).toBeNull()
    fireEvent.change(screen.getByLabelText('Finding'), { target: { value: 'The receipt matches the deed and the title' } })
    fireEvent.change(screen.getByLabelText("The receipt's reference"), { target: { value: 'DEMO-Q-77' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save the record' }))
    await waitFor(() => expect(record).toHaveBeenCalledWith('a1', 'DUTIES_EVIDENCE', { outcome: 'COMPLETED',
      finding: 'The receipt matches the deed and the title', details: { receiptDocument: 'q1', receiptReference: 'DEMO-Q-77' } }, []))
  })

  it('shows a correction beside the record it replaces, and sends a correction with its reason', async () => {
    const visit = { visitedOn: '2026-11-10', members: [{ name: 'DEMO Chief', capacity: 'Village chief' }] }
    vi.spyOn(proceduresApi, 'read').mockResolvedValue(procedure({
      stages: [stage('FIELD_VISIT', {
        status: 'COMPLETED', mayRecord: true, records: [
          rec({ id: 'r2', supersedes: 'r1', correctionReason: 'Markers placed again', details: visit }),
          rec({ id: 'r1', outcome: 'NOT_SATISFIED', finding: 'Markers missing', details: visit }),
        ],
      })],
      gate: [],
    }))
    const record = vi.spyOn(proceduresApi, 'record').mockResolvedValue(procedure({ gate: [] }))
    render(<ProcedurePanel texts={texts} lang="en" applicationId="a1" />)
    expect(await screen.findByText('Corrects the previous record: Markers placed again')).toBeTruthy()
    expect(screen.getByText('Markers missing')).toBeTruthy()
    expect(screen.getByText(/Replaced by a later record/)).toBeTruthy()
    expect(screen.getByText('Every stage is completed: nothing in the procedure stops an approval.')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Correct the latest record' }))
    fireEvent.change(screen.getByLabelText('Finding'), { target: { value: 'A second visit confirmed the markers' } })
    fireEvent.change(screen.getByLabelText(/Commission's members present/), { target: { value: 'DEMO Chief, Village chief' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save the record' }))
    expect(await screen.findByText('Write at least 10 characters.')).toBeTruthy()
    fireEvent.change(screen.getByLabelText('Why this correction'), { target: { value: 'The commission asked for a second visit' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save the record' }))
    await waitFor(() => expect(record).toHaveBeenCalled())
    const sent = record.mock.calls[0][2]
    expect(sent.supersedes).toBe('r2')
    expect(sent.correctionReason).toBe('The commission asked for a second visit')
    expect(sent.details).toMatchObject({ members: [{ name: 'DEMO Chief', capacity: 'Village chief' }] })
  })

  it('renders nothing for a type without a procedure, or for a reader without access', async () => {
    vi.spyOn(proceduresApi, 'read').mockResolvedValue(procedure({ procedure: null, stages: [], gate: [] }))
    const { container } = render(<ProcedurePanel texts={texts} lang="en" applicationId="a1" />)
    await waitFor(() => expect(container.querySelector('[data-procedure]')).toBeNull())
    expect(container.textContent).toBe('')
  })

  it('reads people as "name, capacity" lines and refuses anything else', () => {
    expect(people('DEMO Chief, Village chief\nDEMO Surveyor, Surveyor')).toEqual([
      { name: 'DEMO Chief', capacity: 'Village chief' }, { name: 'DEMO Surveyor', capacity: 'Surveyor' }])
    expect(people('Only a name')).toBeNull()
    expect(people('')).toBeNull()
  })

  it('gives the applicant statuses and dates only', () => {
    render(<StageProgress texts={texts} lang="en" stages={[{ stage: 'CADASTRAL_REVIEW', status: 'COMPLETED', at: '2026-11-11T10:00:00Z' },
      { stage: 'PUBLICATION', status: 'NOT_STARTED', at: null }]} />)
    expect(screen.getByText('Stages of the procedure')).toBeTruthy()
    expect(screen.getByText('Completed')).toBeTruthy()
    expect(screen.getByText('Not started')).toBeTruthy()
  })

  it('publishes without an outcome to choose or a period to give: the setting decides', async () => {
    vi.spyOn(proceduresApi, 'read').mockResolvedValue(procedure({
      stages: [stage('CADASTRAL_REVIEW', { status: 'COMPLETED' }), stage('PUBLICATION', { mayRecord: true })], gate: ['STAGE_MISSING:PUBLICATION'],
    }))
    const record = vi.spyOn(proceduresApi, 'record').mockResolvedValue(published())
    render(<ProcedurePanel texts={texts} lang="en" applicationId="a1" />)
    fireEvent.click(await screen.findByRole('button', { name: 'Record this stage' }))
    expect(screen.getByText(/Publishing opens a notice and its objection period/)).toBeTruthy()
    expect(screen.queryByLabelText('Outcome')).toBeNull()
    fireEvent.change(screen.getByLabelText('Finding'), { target: { value: 'Posted on the office board' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save the record' }))
    await waitFor(() => expect(record).toHaveBeenCalledWith('a1', 'PUBLICATION',
      { outcome: 'COMPLETED', finding: 'Posted on the office board', details: {} }, []))
    expect(await screen.findAllByText('Objection period open')).toHaveLength(2)
    expect(screen.getByText(/Notice DEMO-PUB-2026-000001: parcel DEMO-PCL-YDE1-2026-000001-Z, Nkolbisson/)).toBeTruthy()
    expect(screen.getByText('The objection period is still open')).toBeTruthy()
  })

  it('records an objection at the counter with one retry key, and never shows the identity it was not sent', async () => {
    vi.spyOn(proceduresApi, 'read').mockResolvedValue(published({ objections: [objection()], mayRecordObjection: true,
      gate: ['OBJECTION_PERIOD_OPEN', 'OBJECTION_OPEN'] }))
    const recordObjection = vi.spyOn(proceduresApi, 'recordObjection').mockRejectedValueOnce(new Error('network'))
      .mockResolvedValue({ objection: 'o2', procedure: published() })
    const { container } = render(<ProcedurePanel texts={texts} lang="en" applicationId="a1" />)
    expect(await screen.findByText('DEMO-OBJ-2026-000001')).toBeTruthy()
    expect(screen.getByText('Awaiting decision')).toBeTruthy()
    expect(screen.getByText('DEMO Neighbour Abena, neighbour')).toBeTruthy()
    expect(container.querySelector('[data-objection]')?.textContent).not.toMatch(/NATIONAL_ID/)
    expect(screen.queryByRole('button', { name: 'Show the identity document' })).toBeNull()
    expect(screen.getByText('An objection awaits its decision')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Decide this objection' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Record an objection received at the counter' }))
    fireEvent.click(screen.getByRole('button', { name: 'Record the objection' }))
    expect(await screen.findByText('Write at least 20 characters.')).toBeTruthy()
    expect(recordObjection).not.toHaveBeenCalled()
    fireEvent.change(screen.getByLabelText(/Objector's name/), { target: { value: 'DEMO Heir Ondoa' } })
    fireEvent.change(screen.getByLabelText(/Document number/), { target: { value: 'DEMO-ID-0042' } })
    fireEvent.change(screen.getByLabelText("Objector's interest"), { target: { value: 'HEIR' } })
    fireEvent.change(screen.getByLabelText(/What the objector states/), { target: { value: 'The heirs of the occupant were never heard' } })
    fireEvent.click(screen.getByRole('button', { name: 'Record the objection' }))
    await waitFor(() => expect(recordObjection).toHaveBeenCalledTimes(1))
    fireEvent.click(await screen.findByRole('button', { name: 'Record the objection' }))
    await waitFor(() => expect(recordObjection).toHaveBeenCalledTimes(2))
    const [first, second] = recordObjection.mock.calls
    expect(first[1]).toEqual({ objectorName: 'DEMO Heir Ondoa', identityKind: 'NATIONAL_ID', identityNumber: 'DEMO-ID-0042', interest: 'HEIR',
      statement: 'The heirs of the occupant were never heard' })
    expect(second[3]).toBe(first[3])
  })

  it('decides an objection with grounds, naming the stages to examine again and what the applicant must do', async () => {
    vi.spyOn(proceduresApi, 'read').mockResolvedValue(published({ objections: [objection({
      mayDecide: true, objector: { name: 'DEMO Neighbour Abena', interest: 'NEIGHBOUR', identityVisible: true },
    })] }))
    const identity = vi.spyOn(proceduresApi, 'objectorIdentity').mockResolvedValue({ identityKind: 'NATIONAL_ID', identityNumber: 'DEMO-ID-7731' })
    const decide = vi.spyOn(proceduresApi, 'decideObjection').mockResolvedValue(published({ objections: [objection({
      decision: { outcome: 'UPHELD', grounds: 'The markers must be checked again on site', reviewStages: ['FIELD_VISIT'],
        applicantInstructions: 'Be present at the new visit', decidedByName: 'Sup', decidedBySelf: true, decidedAt: '2026-11-13T11:00:00Z' },
    })] }))
    render(<ProcedurePanel texts={texts} lang="en" applicationId="a1" />)
    expect(screen.queryByText(/DEMO-ID-7731/)).toBeNull()
    fireEvent.click(await screen.findByRole('button', { name: 'Show the identity document' }))
    expect(await screen.findByText(/DEMO-ID-7731/)).toBeTruthy()
    expect(identity).toHaveBeenCalledWith('a1', 'o1')
    fireEvent.click(screen.getByRole('button', { name: 'Decide this objection' }))
    fireEvent.change(screen.getByLabelText('Decision'), { target: { value: 'UPHELD' } })
    expect(screen.getByText(/the case is not refused/)).toBeTruthy()
    fireEvent.change(screen.getByLabelText(/Grounds/), { target: { value: 'The markers must be checked again on site' } })
    fireEvent.click(screen.getByRole('button', { name: 'Record the decision' }))
    expect(await screen.findByText('Choose at least one stage to examine again.')).toBeTruthy()
    expect(decide).not.toHaveBeenCalled()
    fireEvent.click(screen.getByLabelText('Field visit'))
    fireEvent.change(screen.getByLabelText(/What the applicant must do/), { target: { value: 'Be present at the new visit' } })
    fireEvent.click(screen.getByRole('button', { name: 'Record the decision' }))
    await waitFor(() => expect(decide).toHaveBeenCalledWith('a1', 'o1', 'UPHELD', 'The markers must be checked again on site', ['FIELD_VISIT'],
      'Be present at the new visit'))
    expect(await screen.findByText('Upheld')).toBeTruthy()
    expect(screen.getByText('Examined again: Field visit')).toBeTruthy()
    expect(screen.getByText('Asked of the applicant: Be present at the new visit')).toBeTruthy()
  })

  it('tells the applicant which stages are carried out again and what they must do, never why', () => {
    const { container } = render(<StageProgress texts={texts} lang="en" stages={[{ stage: 'FIELD_VISIT', status: 'OUTDATED', at: null }]}
      instructions={[{ stages: ['FIELD_VISIT', 'PUBLICATION'], action: 'Be present at the new visit', at: '2026-11-13T11:00:00Z', pending: true },
        { stages: ['CADASTRAL_REVIEW', 'PUBLICATION'], action: null, at: '2026-11-01T11:00:00Z', pending: false }]} />)
    expect(screen.getByText('Part of the procedure is carried out again')).toBeTruthy()
    expect(screen.getByText('The office carries out again: Field visit, Publication and objections.')).toBeTruthy()
    expect(screen.getByText('What you are asked to do: Be present at the new visit')).toBeTruthy()
    expect(container.querySelectorAll('[data-instruction]')).toHaveLength(1)
    expect(container.textContent).not.toMatch(/objection [^p]|DEMO-OBJ|objector/i)
  })

  it('tells the applicant a stage to record again, and an objection period in progress', () => {
    render(<StageProgress texts={texts} lang="en" stages={[{ stage: 'FIELD_VISIT', status: 'OUTDATED', at: '2026-11-11T10:00:00Z' },
      { stage: 'PUBLICATION', status: 'IN_PROGRESS', at: '2026-11-12T10:00:00Z' }]} />)
    expect(screen.getByText('To be recorded again')).toBeTruthy()
    expect(screen.getByText('Objection period open')).toBeTruthy()
  })

  it('lists the objections waiting for the supervisor', async () => {
    vi.spyOn(proceduresApi, 'objectionQueue').mockResolvedValue([{ objectionId: 'o1', number: 'DEMO-OBJ-2026-000001', applicationId: 'a1',
      reference: 'DEMO-APP-2026-000001-X', officeCode: 'YDE1', receivedAt: '2026-11-13T10:02:00Z', late: true }])
    render(<ObjectionQueuePage ctx={ctx} />)
    expect(await screen.findByText('DEMO-OBJ-2026-000001')).toBeTruthy()
    expect(screen.getByText('Late')).toBeTruthy()
    expect(screen.getByText('DEMO-APP-2026-000001-X')).toBeTruthy()
  })

  it('shows the public what notification needs, and says it is DEMO', async () => {
    vi.spyOn(proceduresApi, 'publicNotices').mockResolvedValue({ demo: true, notices: [{ number: 'DEMO-PUB-2026-000001', procedure: 'A',
      parcelReference: 'DEMO-PCL-YDE1-2026-000001-Z', locality: 'Nkolbisson', office: { code: 'YDE1', nameFr: 'Yaoundé 1', nameEn: 'Yaounde 1' },
      opensAt: '2026-11-13T10:00:00Z', closesAt: '2026-12-13T10:00:00Z' }] })
    const { container } = render(<PublicNoticesPage texts={texts} lang="en" />)
    expect(await screen.findByText('DEMO-PUB-2026-000001')).toBeTruthy()
    expect(screen.getByText('DEMO publication, no legal effect')).toBeTruthy()
    expect(screen.getByText('Nkolbisson')).toBeTruthy()
    expect(screen.getByText(/go in person to the counter of Yaounde 1/)).toBeTruthy()
    expect(container.textContent).not.toMatch(/DEMO-APP-/)
  })

  it('lists the stages waiting for the officer', async () => {
    vi.spyOn(proceduresApi, 'queue').mockResolvedValue([{ applicationId: 'a1', reference: 'DEMO-APP-2026-000001-X', officeCode: 'YDE1',
      typeCode: 'DEMO_IMMATRICULATION', stage: 'FIELD_VISIT', stageStatus: 'NOT_STARTED', submittedAt: '2026-11-10T09:00:00Z' }])
    render(<ProcedureQueuePage ctx={ctx} />)
    expect(await screen.findByText('DEMO-APP-2026-000001-X')).toBeTruthy()
    expect(screen.getByText('Field visit')).toBeTruthy()
  })
})
