// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Public face of the issuance feature (Slice 1, ADR-039; DEMO acknowledgment only): the
// acknowledgment card of an application, the public verification page and the document
// officer's page.
export { AcknowledgmentCard } from './AcknowledgmentCard'
export { issuanceApi } from './api'
export type { Acknowledgment, DocumentStatus, Incident, IssuedDocument, Outcome, Verification } from './api'
export { IssuedDocumentsPage } from './IssuedDocumentsPage'
export { VerifyPage } from './VerifyPage'
