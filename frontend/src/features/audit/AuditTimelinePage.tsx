// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// A case's or a parcel's timeline, from the audit trail alone (E-18, ADR-038), for holders of
// audit.event.search: the auditor reads every event, a supervisor those of their offices (a
// draft's events, before the case had an office, are the auditor's only). Oldest first, each
// event with who acted, the office, what was recorded, and whether its row is intact. The record
// is named in the address (#/audit/timeline?type=APPLICATION&reference=DEMO-APP-...).
import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { fill } from '../../shared/i18n'
import type { Texts } from '../../shared/i18n'
import { href, navigate } from '../../shared/router/router'
import { accessTo } from '../../shared/session/access'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { Card, PageHeader } from '../../shared/ui/Card'
import { Field } from '../../shared/ui/Field'
import { Alert, Badge, EmptyState, Skeleton } from '../../shared/ui/Feedback'
import type { Tone } from '../../shared/ui/Feedback'
import { Icon } from '../../shared/ui/Icon'
import { errorMessage } from '../../shared/ui/errorMessage'
import { ApiError } from '../../shared/api/client'
import { auditApi } from './api'
import type { AuditEvent, Timeline, TimelineType } from './api'

const OUTCOME_TONE: Record<string, Tone> = { SUCCESS: 'success', DENIED: 'danger', FAILED: 'warning' }
const TYPES: TimelineType[] = ['APPLICATION', 'PARCEL']

/** The action in words when the texts know it, its code otherwise. */
function actionWords(texts: Texts, code: string): string | null {
  const words: Record<string, string> = texts.timeline.actions
  return words[code] ?? null
}

export function AuditTimelinePage({ ctx, params }: { ctx: PageContext; params: URLSearchParams }) {
  const { texts, session, lang } = ctx
  const t = texts.timeline
  const type = (TYPES.find((x) => x === params.get('type')) ?? 'APPLICATION') as TimelineType
  const reference = (params.get('reference') ?? '').trim()
  const [typed, setTyped] = useState(reference)
  const [kind, setKind] = useState<TimelineType>(type)
  const [timeline, setTimeline] = useState<Timeline | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [missing, setMissing] = useState(false)
  const everywhere = accessTo(session, 'audit.event.search') !== null

  useEffect(() => {
    if (!reference) return
    let current = true
    auditApi.timeline(type, reference)
      .then((loaded) => { if (current) { setTimeline(loaded); setFailure(null); setMissing(false) } })
      .catch((e) => {
        if (!current) return
        if (e instanceof ApiError && e.code === 'TIMELINE_NOT_FOUND') setMissing(true)
        else setFailure(errorMessage(texts, e))
      })
    return () => { current = false }
  }, [type, reference]) // eslint-disable-line react-hooks/exhaustive-deps

  const open = (e: FormEvent) => {
    e.preventDefault()
    setTimeline(null)
    setMissing(false)
    navigate(href(['audit', 'timeline'], { type: kind, reference: typed.trim() || undefined }))
  }

  const altered = timeline ? timeline.events.filter((e) => e.intact === false).length : 0
  return (
    <div className="page">
      <PageHeader title={t.title} description={t.intro} breadcrumbLabel={texts.shell.breadcrumb}
        crumbs={[{ label: texts.nav.dashboard, href: '#/dashboard' }, { label: texts.nav.audit, href: href(['audit']) },
          { label: reference || t.title }]} />
      {!everywhere && <Alert tone="info">{t.officeOnly}</Alert>}
      <Card padded>
        <form className="filter-bar" role="search" aria-label={t.find} onSubmit={open}>
          <Field label={t.type}>
            <select value={kind} onChange={(e) => setKind(e.target.value as TimelineType)}>
              {TYPES.map((x) => <option key={x} value={x}>{t.types[x]}</option>)}
            </select>
          </Field>
          <Field label={t.reference} className="grow">
            <input value={typed} maxLength={64} className="mono uppercase" onChange={(e) => setTyped(e.target.value.toUpperCase())} />
          </Field>
          <Button type="submit" variant="primary" icon="history">{t.show}</Button>
        </form>
      </Card>

      {!reference && <EmptyState icon="history" title={t.prompt}>{t.promptText}</EmptyState>}
      {failure && <Alert tone="danger">{failure}</Alert>}
      {missing && <EmptyState icon="search" title={t.notFound}>{t.notFoundText}</EmptyState>}
      {reference && !timeline && !failure && !missing && <Skeleton lines={6} />}

      {timeline && (
        <Card title={fill(t.heading, { reference: timeline.reference ?? reference })} icon="history" padded={false}
          description={fill(t.count, { n: timeline.events.length })}
          actions={altered > 0
            ? <Badge tone="danger" icon="alert">{fill(t.alteredCount, { n: altered })}</Badge>
            : <Badge tone="success" icon="checkCircle">{t.allIntact}</Badge>}>
          {timeline.truncated && <div className="card-pad"><Alert tone="warning">{t.truncated}</Alert></div>}
          <ol className="audit-timeline">
            {timeline.events.map((e) => <Entry key={e.id} texts={texts} lang={lang} event={e} />)}
          </ol>
        </Card>
      )}
    </div>
  )
}

function Entry({ texts, lang, event: e }: { texts: Texts; lang: string; event: AuditEvent }) {
  const t = texts.timeline
  const words = actionWords(texts, e.action)
  const offices = Array.from(new Set((e.subjects ?? []).map((s) => s.officeCode).filter((o): o is string => !!o)))
  const who = e.actorType === 'USER'
    ? `${e.actorName ?? e.actorId}${e.actorRoles.length > 0 ? ` (${e.actorRoles.join(', ')})` : ''}`
    : e.actorType === 'SERVICE' ? fill(t.service, { name: e.actorLabel ?? '' }) : t.anonymous
  return (
    <li className={e.intact === false ? 'audit-timeline-entry altered' : 'audit-timeline-entry'} data-action={e.action} data-pos={e.chainPos}>
      <div className="audit-timeline-head">
        <span className="num muted" title={e.occurredAt}>{new Date(e.occurredAt).toLocaleString(lang)}</span>
        <span className="strong">{words ?? e.action}</span>
        {words && <code className="small muted">{e.action}</code>}
        <Badge tone={OUTCOME_TONE[e.outcome] ?? 'neutral'}>{t.outcomes[e.outcome as keyof Texts['timeline']['outcomes']] ?? e.outcome}</Badge>
        {e.intact === false
          ? <Badge tone="danger" icon="alert">{t.altered}</Badge>
          : <Badge tone="neutral" icon="checkCircle">{t.intact}</Badge>}
      </div>
      <p className="small">
        {who} · {offices.length > 0 ? offices.join(', ') : <span className="muted">{t.noOffice}</span>} · <span className="mono">#{e.chainPos}</span>
      </p>
      {e.reason && <p className="small">{e.reason}</p>}
      {Object.keys(e.details ?? {}).length > 0 && (
        <details className="event-details">
          <summary><Icon name="chevronRight" size={14} className="disclosure-icon" />{texts.auditPage.showDetails}</summary>
          <pre><code>{JSON.stringify(e.details, null, 2)}</code></pre>
        </details>
      )}
    </li>
  )
}
