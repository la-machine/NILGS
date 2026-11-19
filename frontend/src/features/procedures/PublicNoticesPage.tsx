// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The public notices of procedures (#/notices, ADR-042 §3), signed in or not: the notices whose objection period is
// open, each with only what public notification needs (the notice's number, the procedure, the parcel's reference and
// locality, the office and the period). No applicant, case or objection is shown, and none is sent by the server.
// An objection is lodged at the office's counter only (P2). DEMO publication, no legal effect.
import { useEffect, useState } from 'react'
import { fill } from '../../shared/i18n'
import type { Lang, Texts } from '../../shared/i18n'
import { Button } from '../../shared/ui/Button'
import { Card } from '../../shared/ui/Card'
import { Alert, EmptyState, Skeleton } from '../../shared/ui/Feedback'
import { errorMessage } from '../../shared/ui/errorMessage'
import { proceduresApi } from './api'
import type { PublicNotice } from './api'

export function PublicNoticesPage({ texts, lang }: { texts: Texts; lang: Lang }) {
  const k = texts.procedures
  const [rows, setRows] = useState<PublicNotice[] | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let current = true
    proceduresApi.publicNotices()
      .then((r) => { if (current) { setRows(r.notices); setFailure(null) } })
      .catch((e) => { if (current) setFailure(errorMessage(texts, e)) })
    return () => { current = false }
  }, [attempt]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="page page-narrow" data-public-notices>
      <h1>{k.publicTitle}</h1>
      <Alert tone="warning" title={k.publicDemoTitle}>{k.publicDemo}</Alert>
      <p>{k.publicIntro}</p>
      {failure && (
        <EmptyState icon="alert" title={failure}
          action={<Button icon="refresh" onClick={() => setAttempt(attempt + 1)}>{texts.common.retry}</Button>}>{null}</EmptyState>
      )}
      {!failure && rows === null && <Skeleton lines={4} />}
      {rows && rows.length === 0 && <EmptyState icon="checkCircle" title={k.publicEmpty}>{null}</EmptyState>}
      {rows && rows.map((n) => (
        <Card key={n.number} title={n.number} icon="file">
          <dl className="properties" data-public-notice={n.number}>
            <dt>{k.publicProcedure}</dt><dd>{k.procedureNames[n.procedure]}</dd>
            <dt>{k.publicParcel}</dt><dd className="mono">{n.parcelReference}</dd>
            <dt>{k.publicLocality}</dt><dd>{n.locality}</dd>
            <dt>{k.publicOffice}</dt><dd>{lang === 'fr' ? n.office.nameFr : n.office.nameEn} ({n.office.code})</dd>
            <dt>{k.publicPeriod}</dt>
            <dd className="num">{fill(k.publicPeriodValue, {
              opens: new Date(n.opensAt).toLocaleString(lang), closes: new Date(n.closesAt).toLocaleString(lang),
            })}</dd>
          </dl>
          <p className="small muted">{fill(k.publicHowToObject, { office: lang === 'fr' ? n.office.nameFr : n.office.nameEn })}</p>
        </Card>
      ))}
    </div>
  )
}
