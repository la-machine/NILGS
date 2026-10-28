// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The first page after sign-in, shaped by the session: what waits for this person's decision,
// what they can start, the latest parcels of their offices, the state of the map data, and
// their own account security. Each block reads real data on its own (one failing never
// blanks the others) and appears only when the session holds the permissions behind it.
import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { formatArea, KINDS } from '../maps'
import type { Background, BackgroundKind } from '../maps'
import type { ParcelSummary } from '../parcels'
import { fill } from '../../shared/i18n'
import type { Texts } from '../../shared/i18n'
import { href } from '../../shared/router/router'
import { accessTo, can, canOwn, canSomewhere, homeOffices, reach } from '../../shared/session/access'
import type { PageContext } from '../../shared/session/types'
import { Card, PageHeader } from '../../shared/ui/Card'
import { Badge, EmptyState, Skeleton } from '../../shared/ui/Feedback'
import { Icon } from '../../shared/ui/Icon'
import type { IconName } from '../../shared/ui/Icon'
import { errorMessage } from '../../shared/ui/errorMessage'
import { dashboardApi } from './api'
import type { PendingCounts } from './api'

type Load<T> = { state: 'loading' } | { state: 'error'; message: string } | { state: 'done'; value: T }

/** Reads once; a failure is kept as a message for that block only. */
function useLoad<T>(texts: Texts, read: (() => Promise<T>) | null): Load<T> | null {
  const [result, setResult] = useState<Load<T>>({ state: 'loading' })
  useEffect(() => {
    if (!read) return
    let current = true
    read().then((value) => { if (current) setResult({ state: 'done', value }) })
      .catch((e) => { if (current) setResult({ state: 'error', message: errorMessage(texts, e) }) })
    return () => { current = false }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps
  return read ? result : null
}

export function DashboardPage({ ctx }: { ctx: PageContext }) {
  const { texts, session, lang } = ctx
  const d = texts.dash
  const readsParcels = canSomewhere(session, 'parcel.read')
  const createsParcels = canSomewhere(session, 'parcel.create')
  const readReach = reach(session, 'parcel.read')
  const parcelOffices = (readReach.everywhere ? homeOffices(session) : readReach.offices).slice(0, 2)
  const staff = session.accountType === 'STAFF' && session.authLevel === 'FULL'
  const may = {
    assignments: can(session, 'admin.assignment.read'),
    roleChanges: can(session, 'admin.role.read'),
    grants: can(session, 'admin.grant.read'),
    resets: can(session, 'admin.mfa.reset'),
    confirmations: canSomewhere(session, 'identity.reset.confirm'),
    reconciles: can(session, 'payment.reconcile'),
  }
  // Open alerts are counted at the offices where the person reviews them (the first page of each).
  const alertOffices = reach(session, 'alert.review').offices
  // Cases waiting for completeness review, where the person decides on them.
  const intakeOffices = reach(session, 'completeness.review').offices
  // Cases past the target of their stage, where the person reads the workload.
  const workloadOffices = reach(session, 'report.workload').offices
  const anyPending = Object.values(may).some(Boolean) || alertOffices.length > 0 || intakeOffices.length > 0
    || workloadOffices.length > 0

  // The date the page was opened, read once.
  const [today] = useState(() => new Date())
  const pending = useLoad(texts, anyPending ? () => dashboardApi.pending(may, alertOffices, intakeOffices, workloadOffices) : null)
  const latest = useLoad(texts, readsParcels && parcelOffices.length > 0
    ? () => Promise.all(parcelOffices.map(async (o) => [o, (await dashboardApi.latestParcels(o)).slice(0, 5)] as const))
    : null)
  const backgrounds = useLoad(texts, readsParcels ? dashboardApi.backgrounds : null)
  const keys = useLoad(texts, staff ? dashboardApi.keys : null)

  const roleName = (code: string) => texts.roleNames[code as keyof Texts['roleNames']] ?? code
  const roles = session.grants.filter((g) => g.role !== 'APPLICANT')
    .map((g) => (g.officeCode ? `${roleName(g.role)} · ${g.officeCode}` : roleName(g.role)))

  const actions: { href: string; icon: IconName; title: string; text: string }[] = []
  if (canOwn(session, 'application.create')) {
    actions.push({ href: href(['applications', 'new']), icon: 'plus', title: texts.applications.newTitle, text: d.actionApply })
  }
  if (canOwn(session, 'application.read')) {
    actions.push({ href: href(['applications']), icon: 'file', title: texts.nav.applications, text: d.actionApplications })
  }
  if (createsParcels) actions.push({ href: href(['parcels', 'new']), icon: 'plus', title: texts.newParcelTitle, text: d.actionCreate })
  if (readsParcels) actions.push({ href: href(['parcels']), icon: 'search', title: d.actionSearchTitle, text: d.actionSearch })
  if (canSomewhere(session, 'report.workload')) {
    actions.push({ href: href(['workload']), icon: 'clock', title: texts.nav.workload, text: d.actionWorkload })
  }
  if (can(session, 'payment.reconcile')) {
    actions.push({ href: href(['payments']), icon: 'card', title: texts.nav.payments, text: d.actionPayments })
  }
  if (can(session, 'admin.account.list')) actions.push({ href: href(['accounts']), icon: 'users', title: texts.nav.accounts, text: d.actionAccounts })
  if (accessTo(session, 'audit.chain.verify')) actions.push({ href: href(['audit']), icon: 'shield', title: texts.verify, text: d.actionVerify })
  actions.push({ href: href(['account']), icon: 'key', title: texts.nav.account, text: d.actionAccount })

  return (
    <div className="page">
      <PageHeader title={fill(d.welcome, { name: session.displayName })} breadcrumbLabel={texts.shell.breadcrumb}
        description={roles.length > 0 ? roles.join(' — ') : d.applicantIntro}
        meta={<span className="meta-item"><Icon name="clock" size={14} />{today.toLocaleDateString(lang, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })}</span>} />

      {anyPending && pending && (
        <Card title={d.attention} icon="clock" description={d.attentionIntro}>
          {pending.state === 'loading' && <Skeleton lines={2} />}
          {pending.state === 'error' && <p className="muted">{pending.message}</p>}
          {pending.state === 'done' && <PendingList texts={texts} counts={pending.value} />}
        </Card>
      )}

      <div className="dashboard-grid">
        <Card title={d.start} icon="arrowRight" className="span-2">
          <ul className="action-tiles">
            {actions.map((a) => (
              <li key={a.href}>
                <a href={a.href} className="action-tile">
                  <span className="action-tile-icon"><Icon name={a.icon} /></span>
                  <span><span className="action-tile-title">{a.title}</span><span className="action-tile-text">{a.text}</span></span>
                  <Icon name="chevronRight" size={16} className="action-tile-chevron" />
                </a>
              </li>
            ))}
          </ul>
        </Card>

        {staff && keys && (
          <Card title={d.security} icon="lock">
            {keys.state === 'loading' && <Skeleton lines={2} />}
            {keys.state === 'error' && <p className="muted">{keys.message}</p>}
            {keys.state === 'done' && (
              <div className="stat">
                <span className="stat-value num">{keys.value.keys.length}</span>
                <span className="stat-label">{fill(d.keysRegistered, { required: keys.value.required })}</span>
                {keys.value.keys.length >= keys.value.required
                  ? <Badge tone="success" icon="checkCircle">{d.keysOk}</Badge>
                  : <Badge tone="warning" icon="alert">{d.keysLow}</Badge>}
                {session.specialGrants.length > 0 && (
                  <p className="small muted">{fill(d.grantsInForce, { n: session.specialGrants.length })}</p>
                )}
              </div>
            )}
          </Card>
        )}

        {readsParcels && latest && (
          <Card title={d.latestParcels} icon="parcel" className="span-2" padded={false}
            description={d.latestIntro}
            actions={<a href={href(['parcels'])} className="btn btn-ghost btn-sm">{d.seeAll}<Icon name="chevronRight" size={14} /></a>}>
            {latest.state === 'loading' && <div className="card-pad"><Skeleton lines={4} /></div>}
            {latest.state === 'error' && <EmptyState icon="alert" title={texts.parcel.loadFailed}>{latest.message}</EmptyState>}
            {latest.state === 'done' && <LatestParcels texts={texts} lang={lang} groups={latest.value} canCreate={createsParcels} />}
          </Card>
        )}

        {readsParcels && backgrounds && (
          <Card title={d.mapData} icon="layers" description={d.mapDataIntro}>
            {backgrounds.state === 'loading' && <Skeleton lines={3} />}
            {backgrounds.state === 'error' && <p className="muted">{backgrounds.message}</p>}
            {backgrounds.state === 'done' && <MapStatus texts={texts} backgrounds={backgrounds.value} />}
          </Card>
        )}
      </div>
    </div>
  )
}

function PendingList({ texts, counts }: { texts: Texts; counts: PendingCounts }) {
  const d = texts.dash
  const items: { n: number | undefined; label: string; to: string; icon: IconName }[] = [
    { n: counts.assignments, label: d.pendingAssignments, to: href(['accounts']), icon: 'users' },
    { n: counts.roleChanges, label: d.pendingRoles, to: href(['roles']), icon: 'shield' },
    { n: counts.grants, label: d.pendingGrants, to: href(['grants']), icon: 'key' },
    { n: counts.resetsWaiting, label: d.pendingResets, to: href(['accounts']), icon: 'clock' },
    { n: counts.confirmations, label: d.pendingConfirmations, to: href(['confirmations']), icon: 'userCheck' },
    { n: counts.alerts, label: d.openAlerts, to: href(['alerts']), icon: 'alert' },
    { n: counts.cases, label: d.casesWaiting, to: href(['cases']), icon: 'file' },
    { n: counts.overdue, label: d.overdueCases, to: href(['workload'], { overdue: '1' }), icon: 'clock' },
    { n: counts.paymentExceptions, label: d.openPaymentExceptions, to: href(['payments']), icon: 'card' },
  ]
  const shown = items.filter((i) => i.n !== undefined)
  if (shown.every((i) => i.n === 0)) return <p className="all-clear"><Icon name="checkCircle" />{d.nothingWaiting}</p>
  return (
    <ul className="pending-tiles">
      {shown.map((i) => (
        <li key={i.label}>
          <a href={i.to} className={i.n ? 'pending-tile active' : 'pending-tile'}>
            <span className="pending-count num">{i.n}</span>
            <span className="pending-label"><Icon name={i.icon} size={16} />{i.label}</span>
          </a>
        </li>
      ))}
    </ul>
  )
}

function LatestParcels({ texts, lang, groups, canCreate }: {
  texts: Texts; lang: string; groups: (readonly [string, ParcelSummary[]])[]; canCreate: boolean
}) {
  const d = texts.dash
  const total = groups.reduce((n, [, items]) => n + items.length, 0)
  if (total === 0) {
    return (
      <EmptyState icon="parcel" title={d.noParcels}
        action={canCreate ? <a className="btn btn-primary" href={href(['parcels', 'new'])}>{texts.newParcel}</a> : undefined}>
        {fill(d.noParcelsText, { offices: groups.map(([o]) => o).join(', ') })}
      </EmptyState>
    )
  }
  const rows: ReactNode[] = []
  for (const [office, items] of groups) {
    for (const p of items) {
      rows.push(
        <tr key={p.reference}>
          <td data-label={texts.parcel.reference}><a href={href(['parcels', p.reference])} className="mono strong">{p.reference}</a></td>
          <td data-label={texts.locality}>{p.locality}</td>
          <td data-label={texts.parcel.officeShort}>{office}</td>
          <td data-label={texts.parcel.areaShort} className="num-col num">{formatArea(p.areaM2)}</td>
          <td data-label={texts.parcel.lastChange} className="num">{new Date(p.updatedAt).toLocaleDateString(lang)}</td>
        </tr>,
      )
    }
  }
  return (
    <table className="table table-responsive">
      <thead>
        <tr>
          <th scope="col">{texts.parcel.reference}</th><th scope="col">{texts.locality}</th><th scope="col">{texts.parcel.officeShort}</th>
          <th scope="col" className="num-col">{texts.parcel.areaShort}</th><th scope="col">{texts.parcel.lastChange}</th>
        </tr>
      </thead>
      <tbody>{rows}</tbody>
    </table>
  )
}

function MapStatus({ texts, backgrounds }: { texts: Texts; backgrounds: Background[] }) {
  const label: Record<BackgroundKind, string> = {
    TECHNICAL: texts.viewTechnical, GEOGRAPHIC: texts.viewGeographic, SATELLITE: texts.viewSatellite,
  }
  const reasons: Record<string, string> = texts.mapUnavailable
  return (
    <ul className="status-list">
      {KINDS.map((kind) => {
        const b = backgrounds.find((x) => x.kind === kind)
        const ok = kind === 'TECHNICAL' || !!b?.available
        return (
          <li key={kind}>
            <Icon name={ok ? 'checkCircle' : 'alert'} className={ok ? 'ok' : 'warn'} />
            <span className="status-name">{label[kind]}</span>
            <span className="muted small">
              {kind === 'TECHNICAL' ? texts.dash.alwaysAvailable
                : ok ? (b?.synthetic ? texts.synthetic : b?.name ?? '') : reasons[b?.unavailableReason ?? 'UNREACHABLE'] ?? reasons.UNREACHABLE}
            </span>
          </li>
        )
      })}
    </ul>
  )
}
