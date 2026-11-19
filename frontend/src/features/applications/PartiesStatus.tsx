// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The persons of the applicant's case (ADR-043) and where the office is with each: declared, transcribed, found to
// correspond to the document presented, or not. Always DEMO and never independently verified, as it says; never the
// officer, the files or a check's note.
import type { Texts } from '../../shared/i18n'
import { Card } from '../../shared/ui/Card'
import { Alert, Badge } from '../../shared/ui/Feedback'
import type { CaseParty } from './api'

const TONE = { DECLARED: 'neutral', RECORDED: 'info', MATCHES_DOCUMENT: 'success', DOES_NOT_MATCH: 'danger', DOCUMENT_EXPIRED: 'warning' } as const

export function PartiesStatus({ texts, parties }: { texts: Texts; parties: CaseParty[] | undefined }) {
  if (!parties || parties.length === 0) return null
  const k = texts.declaration
  return (
    <Card title={k.statusTitle} icon="users" description={k.statusIntro}>
      <Alert tone="warning">{k.unverified}</Alert>
      <ul className="plain-list" data-case-parties>
        {parties.map((p) => (
          <li key={p.ref} data-party={p.ref}>
            <strong>{p.fullName}</strong> {p.roles.map((r) => <Badge key={r} tone="neutral">{k.roles[r]}</Badge>)}{' '}
            <Badge tone={TONE[p.status]}>{k.statuses[p.status]}</Badge>
          </li>
        ))}
      </ul>
    </Card>
  )
}
