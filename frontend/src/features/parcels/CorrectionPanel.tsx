// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Correction mode: a new version of the parcel, with a reason kept in the audit trail. The
// version it corrects is sent (expectedVersion): if someone saved another version meanwhile,
// the server refuses (STALE_VERSION) and nothing is overwritten; the officer reloads, checks
// the newer version, and their typed text is kept. Refusals appear next to the field.
import { useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError } from '../../shared/api/client'
import { GeometryPreview, previewOf } from '../maps'
import { fill } from '../../shared/i18n'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { Field } from '../../shared/ui/Field'
import { Alert } from '../../shared/ui/Feedback'
import { Icon } from '../../shared/ui/Icon'
import { parcelsApi } from './api'
import type { ParcelDetail } from './api'
import { fieldError } from './errors'
import type { FieldError } from './errors'
import { readGeometry } from './geometry'
import type { GeometryValue } from './geometry'
import { GeometryInput } from './GeometryInput'

export function CorrectionPanel({ ctx, parcel, onCancel, onSaved, onReload }: {
  ctx: PageContext; parcel: ParcelDetail; onCancel: () => void
  onSaved: (versionNo: number, alerts: number) => Promise<void>; onReload: () => Promise<ParcelDetail | null>
}) {
  const { texts, run } = ctx
  const p = texts.parcel
  const [geometry, setGeometry] = useState<GeometryValue>({ srid: 4326, text: JSON.stringify(parcel.current.geometry) })
  const [locality, setLocality] = useState(parcel.current.locality)
  const [reason, setReason] = useState('')
  const [problem, setProblem] = useState<FieldError | null>(null)
  const [stale, setStale] = useState(false)
  const [submitted, setSubmitted] = useState(false)
  // The version being corrected stays the one the officer started from, even after a reload.
  const [expected, setExpected] = useState(parcel.currentVersion)

  const reading = readGeometry(geometry)
  const preview = reading.state === 'ok' ? previewOf(geometry.srid, reading.facts.coordinates) : null
  const reasonError = submitted && reason.trim().length < 10 ? p.reasonTooShort : null

  const submit = (e: FormEvent) => {
    e.preventDefault()
    setSubmitted(true)
    if (reading.state !== 'ok' || reason.trim().length < 10) return
    setProblem(null)
    void run(async () => {
      try {
        const saved = await parcelsApi.correct(parcel.reference, {
          expectedVersion: expected, srid: geometry.srid, geometry: reading.geometry, locality: locality.trim(), reason: reason.trim(),
        })
        await onSaved(saved.versionNo, saved.alerts.length)
      } catch (error) {
        if (error instanceof ApiError && error.code === 'STALE_VERSION') {
          setStale(true)
          return
        }
        const field = fieldError(texts, error)
        if (!field) throw error
        setProblem(field)
      }
    })
  }

  const reload = () => void run(async () => {
    const fresh = await onReload()
    if (!fresh) return
    setStale(false)
    setExpected(fresh.currentVersion)
  })

  return (
    <section className="card correction-panel" aria-labelledby="correction-title">
      <header className="card-header">
        <div className="card-heading">
          <span className="card-icon warning"><Icon name="edit" size={18} /></span>
          <div>
            <h2 id="correction-title" className="card-title">{p.correctionTitle}</h2>
            <p className="card-description">{fill(p.correctionIntro, { n: expected, next: expected + 1 })}</p>
          </div>
        </div>
      </header>
      <form onSubmit={submit} noValidate className="card-body form">
        {stale && (
          <Alert tone="warning" title={texts.errors.STALE_VERSION}
            action={<Button size="sm" icon="refresh" onClick={reload}>{p.reloadParcel}</Button>}>
            {p.staleText}
          </Alert>
        )}
        <GeometryInput texts={texts} value={geometry} onChange={(v) => { setGeometry(v); if (problem?.field === 'geometry') setProblem(null) }}
          error={problem?.field === 'geometry' ? problem.message : null}
          detail={problem?.field === 'geometry' ? problem.detail : undefined} />
        {preview && (
          <div className="proposed">
            <p className="field-label">{p.proposedBoundary}</p>
            <GeometryPreview texts={texts} srid={preview.srid} coordinates={preview.coordinates} />
          </div>
        )}
        <Field label={texts.locality} required requiredLabel={texts.common.required}
          error={problem?.field === 'locality' ? problem.message : null}>
          <input minLength={2} maxLength={200} value={locality} onChange={(e) => setLocality(e.target.value)} />
        </Field>
        <Field label={texts.correctionReason} required requiredLabel={texts.common.required} hint={p.reasonHelp} error={reasonError}>
          <textarea rows={3} minLength={10} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
        </Field>
        <div className="form-actions">
          <Button onClick={onCancel}>{texts.common.cancel}</Button>
          <Button type="submit" variant="primary" icon="check" disabled={stale}>{texts.saveCorrection}</Button>
        </div>
      </form>
    </section>
  )
}
