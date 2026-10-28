// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Public face of the reports feature: the workload of an office, for its supervisor (MVP-06).
export { reportsApi, WORKING_STATES } from './api'
export type { OpenCase, WorkingState, WorkloadSummary } from './api'
export { formatDuration } from './format'
export { WorkloadPage } from './WorkloadPage'
