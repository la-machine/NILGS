// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Public face of the applications feature: the applicant's pages, the endpoints, and what staff
// screens show of an application alike (answers, completeness, history, wording of items).
export { applicationsApi } from './api'
export type { Application, ApplicationState, ApplicationSummary, ApplicationType, CompletenessStep, Party, Transition } from './api'
export { ApplicationsRoutes } from './ApplicationsRoutes'
export { AnswersCard, CompletenessCard, DecisionCard, HistoryCard } from './CaseRecord'
export { documentLabel, messages, typeName } from './form'
export { STATE_TONE } from './states'
