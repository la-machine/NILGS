// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Recording a parcel, in three steps on one page: where (office, locality, external
// reference), the boundary (coordinate system and GeoJSON, with a preview), and a review
// before recording. The server issues the reference, validates and never repairs the
// geometry, and computes the authoritative area; nothing is said to be saved until it
// answers. A refusal is shown next to the field it concerns, and everything typed is kept.
import { useState } from 'react'
import type { FormEvent } from 'react'
import { formatArea, GeometryPreview, planarArea, previewOf } from '../maps'
import { fill } from '../../shared/i18n'
import type { Texts } from '../../shared/i18n'
import { href, navigate } from '../../shared/router/router'
import { homeOffices, reach } from '../../shared/session/access'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { Card, PageHeader } from '../../shared/ui/Card'
import { Field } from '../../shared/ui/Field'
import { Alert, EmptyState } from '../../shared/ui/Feedback'
import { Icon } from '../../shared/ui/Icon'
import { parcelsApi } from './api'
import { fieldError } from './errors'
import type { FieldError } from './errors'
import { readGeometry } from './geometry'
import type { GeometryValue } from './geometry'
import { GeometryInput } from './GeometryInput'

export function ParcelCreatePage({ ctx }: { ctx: PageContext }) {
  const { texts, session, run } = ctx
  const p = texts.parcel
  const create = reach(session, 'parcel.create')
  const offices = create.everywhere ? homeOffices(session) : create.offices
  const [office, setOffice] = useState(offices[0] ?? '')
  const [locality, setLocality] = useState('')
  const [externalReference, setExternalReference] = useState('')
  const [geometry, setGeometry] = useState<GeometryValue>({ srid: 4326, text: '' })
  // One key per form: a retry after a timeout cannot create the parcel twice (ADR-013).
  const [key] = useState(() => crypto.randomUUID())
  const [problem, setProblem] = useState<FieldError | null>(null)
  const [submitted, setSubmitted] = useState(false)

  const reading = readGeometry(geometry)
  const preview = reading.state === 'ok' ? previewOf(geometry.srid, reading.facts.coordinates) : null
  const localityError = submitted && locality.trim().length < 2 ? p.localityRequired : null

  const submit = (e: FormEvent) => {
    e.preventDefault()
    setSubmitted(true)
    if (reading.state !== 'ok' || locality.trim().length < 2 || !office) return
    setProblem(null)
    void run(async () => {
      try {
        const created = await parcelsApi.create({
          officeCode: office, locality: locality.trim(), externalReference: externalReference.trim() || undefined,
          srid: geometry.srid, geometry: reading.geometry,
        }, key)
        navigate(href(['parcels', created.reference], {
          created: created.areaM2.toFixed(2), alerts: created.alerts.length ? String(created.alerts.length) : undefined,
        }))
      } catch (error) {
        const field = fieldError(texts, error)
        if (!field) throw error
        setProblem(field)
      }
    })
  }

  const errorFor = (field: FieldError['field']) => (problem?.field === field ? problem.message : null)

  return (
    <div className="page">
      <PageHeader title={texts.newParcelTitle} description={texts.newParcelIntro} breadcrumbLabel={texts.shell.breadcrumb}
        crumbs={[{ label: texts.nav.dashboard, href: '#/dashboard' }, { label: texts.nav.parcels, href: '#/parcels' }, { label: texts.newParcel }]} />
      <form onSubmit={submit} noValidate className="create-layout">
        <div className="create-main">
          <Card title={`1. ${p.stepWhere}`} description={p.stepWhereHelp}>
            <div className="form-grid">
              <Field label={texts.office} error={errorFor('office')} hint={p.officeHelp}>
                <select value={office} onChange={(e) => setOffice(e.target.value)}>
                  {offices.map((o) => <option key={o} value={o}>{o}</option>)}
                </select>
              </Field>
              <Field label={texts.locality} required requiredLabel={texts.common.required} hint={p.localityHelp}
                error={errorFor('locality') ?? localityError}>
                <input minLength={2} maxLength={200} value={locality} onChange={(e) => setLocality(e.target.value)} />
              </Field>
              <Field label={texts.externalReference} hint={p.externalHelp} error={errorFor('externalReference')} className="span-2">
                <input maxLength={100} value={externalReference} onChange={(e) => setExternalReference(e.target.value)} />
              </Field>
            </div>
          </Card>

          <Card title={`2. ${p.stepBoundary}`} description={p.stepBoundaryHelp}>
            <GeometryInput texts={texts} value={geometry} onChange={(v) => { setGeometry(v); if (problem?.field === 'geometry') setProblem(null) }}
              error={errorFor('geometry') ?? (submitted && reading.state === 'empty' ? p.geometryRequired : null)}
              detail={problem?.field === 'geometry' ? problem.detail : undefined} />
          </Card>

          <Card title={`3. ${p.stepReview}`} description={p.stepReviewHelp}>
            <Summary texts={texts} office={office} locality={locality} externalReference={externalReference}
              srid={geometry.srid} reading={reading} indicative={preview ? planarArea(preview.coordinates) : null} />
            <div className="form-actions">
              <a href={href(['parcels'])} className="btn btn-secondary">{texts.common.cancel}</a>
              <Button type="submit" variant="primary" icon="check">{texts.createParcel}</Button>
            </div>
          </Card>
        </div>

        <aside className="create-aside">
          <Card title={p.previewTitle} icon="eye">
            {preview
              ? <GeometryPreview texts={texts} srid={preview.srid} coordinates={preview.coordinates} />
              : <EmptyState icon="parcel" title={p.previewEmpty}>{p.previewEmptyText}</EmptyState>}
          </Card>
          <Card title={p.nextTitle} icon="info">
            <ol className="next-steps">
              <li>{p.next1}</li>
              <li>{p.next2}</li>
              <li>{p.next3}</li>
              <li>{p.next4}</li>
            </ol>
          </Card>
        </aside>
      </form>
    </div>
  )
}

function Summary({ texts, office, locality, externalReference, srid, reading, indicative }: {
  texts: Texts; office: string; locality: string; externalReference: string; srid: number
  reading: ReturnType<typeof readGeometry>; indicative: number | null
}) {
  const p = texts.parcel
  const ready = reading.state === 'ok' && locality.trim().length >= 2 && !!office
  return (
    <>
      <dl className="summary">
        <div><dt>{texts.office}</dt><dd>{office || '—'}</dd></div>
        <div><dt>{texts.locality}</dt><dd>{locality.trim() || '—'}</dd></div>
        <div><dt>{texts.externalReference}</dt><dd>{externalReference.trim() || '—'}</dd></div>
        <div><dt>{texts.crs}</dt><dd>EPSG:{srid}</dd></div>
        <div>
          <dt>{p.boundary}</dt>
          <dd>{reading.state === 'ok'
            ? fill(p.factShape, { type: reading.facts.type, polygons: reading.facts.polygons, vertices: reading.facts.vertices })
            : '—'}</dd>
        </div>
        <div>
          <dt>{p.indicativeArea}</dt>
          <dd className="num">{indicative !== null ? `≈ ${formatArea(indicative)}` : '—'}</dd>
        </div>
      </dl>
      {ready
        ? <Alert tone="info">{p.reviewReady}</Alert>
        : <p className="muted review-missing"><Icon name="info" size={16} />{p.reviewMissing}</p>}
    </>
  )
}
