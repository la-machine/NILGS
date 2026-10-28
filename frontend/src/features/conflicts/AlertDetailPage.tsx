// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The review of one alert (MVP-04, E-11): the two compared parcel versions on one map with
// their intersection, the two parcels side by side (version compared and current version,
// author, area, share covered), then the resolution: a form for an entitled reviewer who
// authored neither version, the reason why not for anyone else, or the outcome once decided.
import { useEffect, useState } from 'react'
import { formatArea } from '../maps'
import { fill } from '../../shared/i18n'
import type { Texts } from '../../shared/i18n'
import { href } from '../../shared/router/router'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { Card, PageHeader } from '../../shared/ui/Card'
import { Alert, Badge, EmptyState, Skeleton } from '../../shared/ui/Feedback'
import { errorMessage } from '../../shared/ui/errorMessage'
import { AlertMap } from './AlertMap'
import { conflictsApi } from './api'
import type { AlertDetail, AlertSide } from './api'
import { ResolutionPanel } from './ResolutionPanel'
import { percent, resolveAccess, STATUS_TONE } from './rules'

export function AlertDetailPage({ ctx, id }: { ctx: PageContext; id: string }) {
  const { texts, session, lang } = ctx
  const c = texts.conflicts
  const [alert, setAlert] = useState<AlertDetail | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [justResolved, setJustResolved] = useState(false)

  const [attempt, setAttempt] = useState(0)
  const reload = () => setAttempt((n) => n + 1)

  useEffect(() => {
    let current = true
    conflictsApi.read(id)
      .then((loaded) => { if (current) { setAlert(loaded); setFailure(null) } })
      .catch((e) => { if (current) setFailure(errorMessage(texts, e)) })
    return () => { current = false }
  }, [id, attempt]) // eslint-disable-line react-hooks/exhaustive-deps

  const crumbs = [
    { label: texts.nav.dashboard, href: '#/dashboard' },
    { label: texts.nav.alerts, href: href(['alerts']) },
    { label: c.reviewCrumb },
  ]

  if (failure) {
    return (
      <div className="page">
        <PageHeader title={c.reviewCrumb} breadcrumbLabel={texts.shell.breadcrumb} crumbs={crumbs} />
        <EmptyState icon="alert" title={c.loadFailed}
          action={<Button icon="refresh" onClick={reload}>{texts.common.retry}</Button>}>{failure}</EmptyState>
      </div>
    )
  }
  if (!alert) {
    return <div className="page" aria-busy="true"><Skeleton lines={8} /></div>
  }

  return (
    <div className="page">
      <PageHeader
        title={fill(c.reviewTitle, { type: c.types[alert.type] })}
        description={fill(c.reviewIntro, { a: alert.a.reference, b: alert.b.reference })}
        breadcrumbLabel={texts.shell.breadcrumb} crumbs={crumbs}
        meta={(
          <>
            <Badge tone={STATUS_TONE[alert.status]}>{c.statusNames[alert.status]}</Badge>
            <span className="num">{fill(c.raisedOnBy, {
              date: new Date(alert.raisedAt).toLocaleString(lang), reference: alert.raisedByReference,
            })}</span>
          </>
        )} />

      {justResolved && <Alert tone="success" title={c.resolvedTitle}>{c.resolvedText}</Alert>}

      <Card title={c.comparison} icon="map" padded={false}
        description={alert.intersectionM2 !== null
          ? fill(c.geometryEvidence, { area: alert.intersectionM2.toFixed(2), a: percent(alert.a.share), b: percent(alert.b.share) })
          : fill(c.referenceFacts, { reference: alert.normalizedReference ?? '' })}>
        {alert.display
          ? <AlertMap texts={texts} alert={{ ...alert, display: alert.display }} />
          : <p className="card-pad muted">{c.noMap}</p>}
      </Card>

      <div className="alert-sides">
        <SideCard texts={texts} side={alert.a} label="A" className="alert-side" />
        <SideCard texts={texts} side={alert.b} label="B" className="alert-side side-b" />
      </div>

      {alert.status === 'OPEN' && (
        <ResolutionPanel key={alert.version} texts={texts} alert={alert} access={resolveAccess(session, alert)}
          onReload={reload}
          onResolved={(resolved) => {
            setAlert(resolved)
            setJustResolved(true)
            void ctx.refreshSession()
          }} />
      )}
      {alert.status === 'RESOLVED' && (
        <Card title={c.outcomeTitle} icon="checkCircle">
          <dl className="properties">
            <div><dt>{c.outcome}</dt><dd className="strong">{alert.resolution && c.resolutions[alert.resolution]}</dd></div>
            <div><dt>{c.note}</dt><dd className="pre-line">{alert.resolutionNote}</dd></div>
            <div><dt>{c.resolvedBy}</dt><dd>{alert.resolvedByName}</dd></div>
            <div><dt>{c.resolvedOn}</dt><dd className="num">{alert.resolvedAt && new Date(alert.resolvedAt).toLocaleString(lang)}</dd></div>
          </dl>
        </Card>
      )}
      {alert.status === 'SUPERSEDED' && (
        <Alert tone="info" title={c.supersededTitle}>
          {fill(c.supersededText, { date: alert.supersededAt ? new Date(alert.supersededAt).toLocaleString(lang) : '' })}
        </Alert>
      )}
    </div>
  )
}

function SideCard({ texts, side, label, className }: { texts: Texts; side: AlertSide; label: string; className: string }) {
  const c = texts.conflicts
  const changed = side.currentVersionNo !== side.versionNo
  return (
    <Card className={className} title={fill(c.parcelLabel, { label })}>
      <dl className="properties">
        <div>
          <dt>{c.reference}</dt>
          <dd><a href={href(['parcels', side.reference], { version: changed ? String(side.versionNo) : undefined })}
            className="mono strong">{side.reference}</a></dd>
        </div>
        <div><dt>{texts.office}</dt><dd>{side.officeCode}</dd></div>
        <div>
          <dt>{c.comparedVersion}</dt>
          <dd>
            v{side.versionNo}{' '}
            {changed
              ? <Badge tone="info">{fill(c.currentIs, { n: side.currentVersionNo })}</Badge>
              : <span className="muted">{c.stillCurrent}</span>}
          </dd>
        </div>
        <div><dt>{c.author}</dt><dd>{side.authorName}</dd></div>
        <div><dt>{texts.area}</dt><dd className="num">{formatArea(side.areaM2)}</dd></div>
        {side.share !== null && <div><dt>{c.shareCovered}</dt><dd className="num strong">{percent(side.share)}</dd></div>}
      </dl>
    </Card>
  )
}
