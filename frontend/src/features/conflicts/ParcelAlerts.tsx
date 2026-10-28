// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The overlap and duplicate alerts of one parcel, for its reviewers (alert.review at its
// office): open ones first, each with the other parcel, both compared versions and, for a
// geometric alert, the intersection and the share of each parcel it covers. Closed ones
// (resolved, or superseded by a correction) are kept below as history. Each opens its review
// (#/alerts/<id>, E-11). An alert is evidence for review, never a finding.
import { useEffect, useState } from 'react'
import { fill } from '../../shared/i18n'
import type { Lang, Texts } from '../../shared/i18n'
import { href } from '../../shared/router/router'
import { Card } from '../../shared/ui/Card'
import { Badge, EmptyState, Skeleton } from '../../shared/ui/Feedback'
import { errorMessage } from '../../shared/ui/errorMessage'
import { conflictsApi } from './api'
import type { ParcelAlert } from './api'
import { percent, STATUS_TONE } from './rules'

export function ParcelAlerts({ texts, lang, reference, versionNo }: {
  texts: Texts; lang: Lang; reference: string
  /** The parcel's current version: reloads when a correction changes it. */
  versionNo: number
}) {
  const c = texts.conflicts
  const [alerts, setAlerts] = useState<ParcelAlert[] | null>(null)
  const [failure, setFailure] = useState<string | null>(null)

  useEffect(() => {
    let current = true
    conflictsApi.ofParcel(reference)
      .then((list) => { if (current) { setAlerts(list); setFailure(null) } })
      .catch((e) => { if (current) setFailure(errorMessage(texts, e)) })
    return () => { current = false }
  }, [reference, versionNo]) // eslint-disable-line react-hooks/exhaustive-deps

  const open = alerts?.filter((a) => a.status === 'OPEN') ?? []
  const closed = alerts?.filter((a) => a.status !== 'OPEN') ?? []

  const line = (a: ParcelAlert) => (
    <li key={a.id} className="request" data-alert={a.id}>
      <div className="request-main">
        <p className="request-title">
          <Badge tone={a.type === 'OVERLAP' ? 'warning' : 'danger'} icon="alert">{c.types[a.type]}</Badge>
          <span>{c.with}</span>
          <a href={href(['parcels', a.otherReference])} className="mono strong">{a.otherReference}</a>
          <span className="muted">({a.otherOfficeCode})</span>
          {a.status !== 'OPEN' && <Badge tone={STATUS_TONE[a.status]}>{c.statuses[a.status]}</Badge>}
        </p>
        <p className="request-meta">
          {a.intersectionM2 !== null
            ? fill(c.geometryFacts, { area: a.intersectionM2.toFixed(2), self: percent(a.shareOfThisParcel), other: percent(a.shareOfOtherParcel) })
            : fill(c.referenceFacts, { reference: a.normalizedReference ?? '' })}
        </p>
        <p className="request-meta">
          {fill(c.versions, { self: a.thisVersionNo, other: a.otherVersionNo })}
          {' · '}<span className="num">{fill(c.raisedOn, { date: new Date(a.raisedAt).toLocaleString(lang) })}</span>
        </p>
        {a.resolution && <p className="request-reason">{c.resolutions[a.resolution]} — {a.resolutionNote}</p>}
      </div>
      <div className="request-actions">
        <a className="btn btn-sm btn-secondary" href={href(['alerts', a.id])}>{a.status === 'OPEN' ? c.review : c.open}</a>
      </div>
    </li>
  )

  return (
    <Card title={c.title} icon="alert" description={c.intro}
      actions={alerts && <Badge tone={open.length > 0 ? 'warning' : 'success'}>{fill(c.openCount, { n: open.length })}</Badge>}>
      {failure && <p className="muted">{failure}</p>}
      {!failure && alerts === null && <Skeleton lines={2} />}
      {alerts && alerts.length === 0 && <EmptyState icon="checkCircle" title={c.none}>{c.noneText}</EmptyState>}
      {open.length > 0 && <ul className="request-list">{open.map(line)}</ul>}
      {closed.length > 0 && (
        <details className="disclosure">
          <summary>{fill(c.history, { n: closed.length })}</summary>
          <ul className="request-list">{closed.map(line)}</ul>
        </details>
      )}
    </Card>
  )
}
