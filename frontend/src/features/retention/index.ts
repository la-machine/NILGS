// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Public face of the retention feature (Slice 3, ADR-041 D18): the retention and legal holds page, and the
// holds panel that the professional administration screen shows beside the evidence.
export { retentionApi } from './api'
export type { Hold, RecordRetention, RetentionClass, SubjectKind } from './api'
export { HoldsPanel } from './HoldsPanel'
export { RetentionPage } from './RetentionPage'
