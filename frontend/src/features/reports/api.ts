// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The workload report of an office (MVP-06, ADR-036), for holders of report.workload there:
// counts per state, cases under way against the DEMO target of their stage, the load per officer
// and the stage durations. Every time is computed by the server from the history of states;
// durations come in whole seconds.
import { call } from '../../shared/api/client'
import type { ApplicationState } from '../applications'

/** The states a case waits in before it ends: the stages of the report. */
export const WORKING_STATES = ['SUBMITTED', 'COMPLETENESS_REVIEW', 'INFO_REQUESTED', 'UNDER_REVIEW', 'PENDING_DECISION'] as const
export type WorkingState = (typeof WORKING_STATES)[number]

export interface WorkloadSummary {
  office: string
  asOf: string
  /** The start of the period the durations cover. */
  from: string
  targets: { state: WorkingState; seconds: number }[]
  counts: { state: ApplicationState; cases: number }[]
  open: { state: WorkingState; open: number; overdue: number }[]
  assignees: { name: string; state: WorkingState; cases: number; overdue: number }[]
  durations: { state: WorkingState; stays: number; averageSeconds: number; medianSeconds: number; longestSeconds: number; overTarget: number }[]
}

export interface OpenCase {
  id: string
  reference: string
  parcelReference: string
  state: WorkingState
  since: string
  waitedSeconds: number
  /** null when the stage has no target. */
  targetSeconds: number | null
  overdue: boolean
  /** null while nobody holds the case. */
  assigneeName: string | null
}

export const reportsApi = {
  workload: (office: string) => call<WorkloadSummary>('GET', `/reports/workload?${new URLSearchParams({ office })}`),
  cases: (office: string, state: WorkingState | '', overdue: boolean, page: number) => {
    const query = new URLSearchParams({ office, page: String(page) })
    if (state) query.set('state', state)
    if (overdue) query.set('overdue', 'true')
    return call<OpenCase[]>('GET', `/reports/workload/cases?${query}`)
  },
}
