// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// One parcel: identity and status, the map workspace (three views of the authoritative
// geometry), its properties and the history of its versions. Viewing and correcting are
// distinct modes: correction is entered on purpose (#...?mode=correct), says that it creates
// a new version, and never overwrites one saved meanwhile. A past version opens read-only
// (#...?version=2). The chosen map view is kept in the address (#...?view=satellite), so
// switching views never reloads or changes the parcel.
import { useEffect, useState } from 'react'
import { ParcelAlerts } from '../conflicts'
import { MapView } from '../maps'
import type { BackgroundKind } from '../maps'
import { fill } from '../../shared/i18n'
import { href, navigate } from '../../shared/router/router'
import { canAt } from '../../shared/session/access'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { Card, PageHeader } from '../../shared/ui/Card'
import { Alert, Badge, EmptyState, Skeleton } from '../../shared/ui/Feedback'
import { errorMessage } from '../../shared/ui/errorMessage'
import { formatArea } from '../maps'
import { ApiError } from '../../shared/api/client'
import { parcelsApi } from './api'
import type { ParcelDetail, ParcelVersion } from './api'
import { CorrectionPanel } from './CorrectionPanel'
import { VersionHistory } from './VersionHistory'

const VIEWS: Record<string, BackgroundKind> = { technical: 'TECHNICAL', geographic: 'GEOGRAPHIC', satellite: 'SATELLITE' }

export function ParcelDetailPage({ ctx, reference, params }: { ctx: PageContext; reference: string; params: URLSearchParams }) {
  const { texts, session, lang } = ctx
  const p = texts.parcel
  const [parcel, setParcel] = useState<ParcelDetail | null>(null)
  const [failure, setFailure] = useState<{ notFound: boolean; message: string } | null>(null)
  const [past, setPast] = useState<ParcelVersion | null>(null)
  const [saved, setSaved] = useState<{ versionNo: number; alerts: number } | null>(null)
  const [attempt, setAttempt] = useState(0)

  const versionParam = Number(params.get('version') ?? 0) || null
  const view = VIEWS[params.get('view') ?? ''] ?? 'TECHNICAL'
  const correcting = params.get('mode') === 'correct'
  const created = params.get('created')
  const raised = Number(params.get('alerts') ?? 0) || 0

  const at = (next: Record<string, string | undefined>) => {
    const current = Object.fromEntries(params.entries())
    navigate(href(['parcels', reference], { ...current, created: undefined, alerts: undefined, ...next }), { replace: true })
  }

  const load = (): Promise<ParcelDetail | null> => parcelsApi.read(reference)
    .then((detail) => {
      setParcel(detail)
      setFailure(null)
      return detail
    })
    .catch((e) => {
      setFailure({ notFound: e instanceof ApiError && (e.status === 404 || e.status === 403), message: errorMessage(texts, e) })
      return null
    })

  useEffect(() => { void load() }, [attempt]) // eslint-disable-line react-hooks/exhaustive-deps

  // A past version is read on demand; the current one comes with the parcel.
  const wantsPast = !!parcel && !!versionParam && versionParam !== parcel.currentVersion
  useEffect(() => {
    if (!wantsPast || !versionParam) return
    let current = true
    parcelsApi.version(reference, versionParam).then((v) => { if (current) setPast(v) })
      .catch((e) => { if (current) setFailure({ notFound: false, message: errorMessage(texts, e) }) })
    return () => { current = false }
  }, [wantsPast, versionParam]) // eslint-disable-line react-hooks/exhaustive-deps

  const shown: ParcelVersion | null = !parcel ? null
    : !wantsPast ? parcel.current
    : past?.versionNo === versionParam ? past : null

  const crumbs = [{ label: texts.nav.dashboard, href: '#/dashboard' }, { label: texts.nav.parcels, href: '#/parcels' }, { label: reference }]

  if (failure && !parcel) {
    return (
      <div className="page">
        <PageHeader title={reference} breadcrumbLabel={texts.shell.breadcrumb} crumbs={crumbs} />
        <Card>
          <EmptyState icon={failure.notFound ? 'parcel' : 'alert'} title={failure.notFound ? texts.errors.PARCEL_NOT_FOUND : p.loadFailed}
            action={failure.notFound
              ? <a className="btn btn-secondary" href={href(['parcels'])}>{p.backToList}</a>
              : <Button icon="refresh" onClick={() => setAttempt(attempt + 1)}>{texts.common.retry}</Button>}>
            {failure.notFound ? p.notFoundText : failure.message}
          </EmptyState>
        </Card>
      </div>
    )
  }

  if (!parcel || !shown) {
    return (
      <div className="page" aria-busy="true">
        <PageHeader title={reference} breadcrumbLabel={texts.shell.breadcrumb} crumbs={crumbs} />
        <div className="detail-layout">
          <Card><Skeleton lines={8} /></Card>
          <Card><Skeleton lines={6} /></Card>
        </div>
      </div>
    )
  }

  const historical = shown.versionNo !== parcel.currentVersion
  const canCorrect = canAt(session, 'parcel.correct', parcel.officeCode)
  const canReviewAlerts = canAt(session, 'alert.review', parcel.officeCode)

  return (
    <div className="page" data-reference={parcel.reference}>
      <PageHeader breadcrumbLabel={texts.shell.breadcrumb} crumbs={crumbs}
        title={<span className="mono">{parcel.reference}</span>}
        description={shown.locality}
        meta={
          <>
            <Badge tone={parcel.status === 'ACTIVE' ? 'success' : 'neutral'} icon={parcel.status === 'ACTIVE' ? 'checkCircle' : 'info'}>
              {p.status[parcel.status] ?? parcel.status}
            </Badge>
            <Badge tone={shown.verificationStatus === 'VERIFIED' ? 'success' : 'warning'}>
              {p.verification[shown.verificationStatus] ?? shown.verificationStatus}
            </Badge>
            <span className="meta-item"><span className="visually-hidden">{texts.office}: </span>{parcel.officeCode}</span>
            <span className="meta-item">{fill(texts.versionOf, { n: shown.versionNo, total: parcel.currentVersion })}</span>
          </>
        }
        actions={canCorrect && !historical && !correcting && (
          <Button variant="primary" icon="edit" onClick={() => at({ mode: 'correct' })}>{texts.correctParcel}</Button>
        )} />

      {created && <Alert tone="success" title={fill(texts.parcelCreated, { reference: parcel.reference, area: created })}>{p.createdText}</Alert>}
      {created && raised > 0 && <Alert tone="warning" title={fill(texts.conflicts.raisedTitle, { n: raised })}>{texts.conflicts.raisedText}</Alert>}
      {saved && <Alert tone="success" onDismiss={() => setSaved(null)} dismissLabel={texts.shell.dismiss}>{fill(p.correctionSaved, { n: saved.versionNo })}</Alert>}
      {saved && saved.alerts > 0 && <Alert tone="warning" title={fill(texts.conflicts.raisedTitle, { n: saved.alerts })}>{texts.conflicts.raisedText}</Alert>}
      {historical && (
        <Alert tone="warning" title={fill(texts.historicalVersion, { n: shown.versionNo })}
          action={<Button size="sm" onClick={() => at({ version: undefined })}>{p.backToCurrent}</Button>}>
          {p.historicalText}
        </Alert>
      )}

      <div className={correcting ? 'detail-layout correcting' : 'detail-layout'}>
        <Card title={p.mapTitle} icon="map" className="map-card" padded={false}>
          <MapView key={`${parcel.reference}-${shown.versionNo}`} texts={texts} geometry={shown.geometry} display={shown.display}
            areaM2={shown.areaM2} view={view}
            onViewChange={(v) => at({ view: v === 'TECHNICAL' ? undefined : v.toLowerCase() })} />
        </Card>

        <div className="detail-aside">
          {correcting && canCorrect && !historical
            ? (
              <CorrectionPanel ctx={ctx} parcel={parcel}
                onCancel={() => at({ mode: undefined })}
                onSaved={async (versionNo, alerts) => {
                  await load()
                  setSaved({ versionNo, alerts })
                  at({ mode: undefined, version: undefined })
                }}
                onReload={load} />
            )
            : (
              <>
                <Card title={p.properties} icon="info">
                  <dl className="properties">
                    <div><dt>{texts.locality}</dt><dd>{shown.locality}</dd></div>
                    {shown.externalReference && <div><dt>{texts.externalReference}</dt><dd>{shown.externalReference}</dd></div>}
                    <div><dt>{texts.area}</dt><dd className="num strong">{formatArea(shown.areaM2)}</dd></div>
                    <div><dt>{texts.sourceCrs}</dt><dd>EPSG:{shown.sourceSrid}</dd></div>
                    <div><dt>{texts.office}</dt><dd>{parcel.officeCode}</dd></div>
                    <div><dt>{p.recordedOn}</dt><dd className="num">{new Date(parcel.createdAt).toLocaleString(lang)}</dd></div>
                    <div><dt>{p.versionSaved}</dt><dd className="num">{new Date(shown.createdAt).toLocaleString(lang)}</dd></div>
                  </dl>
                </Card>
                {canReviewAlerts && <ParcelAlerts texts={texts} lang={lang} reference={parcel.reference} versionNo={parcel.currentVersion} />}
                <VersionHistory texts={texts} lang={lang} parcel={parcel} shown={shown.versionNo}
                  onShow={(n) => at({ version: n === parcel.currentVersion ? undefined : String(n), mode: undefined })} />
              </>
            )}
        </div>
      </div>
    </div>
  )
}
