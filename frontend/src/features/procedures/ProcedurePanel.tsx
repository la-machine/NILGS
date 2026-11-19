// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// A case's procedure for staff (ADR-042): each stage with its records, newest first, a correction naming the record it
// replaces (both kept); the attached files as downloads; what still stops an approval. An officer who may record a
// stage now (the server says so) records it, or corrects its latest record with a reason. The publication's notice and
// the objections follow the stages (step 4). Nothing is rendered for a case whose type has no procedure. DEMO only.
import { useEffect, useState } from 'react'
import { fill } from '../../shared/i18n'
import type { Lang, Texts } from '../../shared/i18n'
import { Button } from '../../shared/ui/Button'
import { Card } from '../../shared/ui/Card'
import { Alert, Badge, Skeleton } from '../../shared/ui/Feedback'
import { Field } from '../../shared/ui/Field'
import { errorMessage } from '../../shared/ui/errorMessage'
import { FINDING_MAX, FINDING_MIN, MAX_FILES, proceduresApi, REASON_MAX, REASON_MIN } from './api'
import type { CaseProcedure, NewRecord, Outcome, Person, Stage, StageRecord } from './api'
import { NoticeSummary, ObjectionsSection } from './Objections'
import { ReconsiderationSection } from './Reconsideration'
import { gateReason, people, STATUS_TONE } from './stages'

export function ProcedurePanel({ texts, lang, applicationId }: { texts: Texts; lang: Lang; applicationId: string }) {
  const k = texts.procedures
  const [data, setData] = useState<CaseProcedure | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [loaded, setLoaded] = useState(false)
  const [editing, setEditing] = useState<{ stage: Stage; correcting: StageRecord | null } | null>(null)

  useEffect(() => {
    let current = true
    proceduresApi.read(applicationId)
      .then((d) => { if (current) { setData(d); setLoaded(true) } })
      // A reader without the procedure's access sees the rest of the case: nothing to show here.
      .catch(() => { if (current) setLoaded(true) })
    return () => { current = false }
  }, [applicationId])

  if (!loaded) return <Skeleton lines={3} />
  if (!data || !data.procedure) return null

  const saved = (d: CaseProcedure) => { setData(d); setEditing(null); setFailure(null) }

  return (
    <Card title={k.procedureNames[data.procedure]} icon="checkCircle" description={k.panelIntro}>
      <div data-procedure={data.procedure}>
        <Alert tone="info">{k.demoNotice}</Alert>
        {failure && <Alert tone="danger">{failure}</Alert>}
        {data.gate.length > 0
          ? (
            <Alert tone="warning" title={k.gateTitle}>
              <ul className="plain-list" data-gate>
                {data.gate.map((g) => <li key={g} data-problem={g}>{gateReason(texts, g)}</li>)}
              </ul>
              <div className="small">{k.gateRefusalStillPossible}</div>
            </Alert>
          )
          : <Alert tone="success">{k.gateClear}</Alert>}
        <ol className="plain-list stage-list">
          {data.stages.map((s) => (
            <li key={s.code} data-stage={s.code} data-status={s.status} className="block">
              <div className="exception-head">
                <Badge tone={STATUS_TONE[s.status]}>{k.statuses[s.status]}</Badge>
                <strong>{lang === 'fr' ? s.labelFr : s.labelEn}</strong>
              </div>
              {s.code === 'COMMISSION_REPORT' && <div className="small muted">{k.reportOnlySecretary}</div>}
              {s.code === 'PUBLICATION' && <NoticeSummary texts={texts} lang={lang} notices={data.notices} />}
              {s.records.length === 0 && <p className="small muted">{k.noRecord}</p>}
              {s.records.length > 0 && (
                <ul className="plain-list replacement-list">
                  {s.records.map((r, i) => (
                    <RecordItem key={r.id} texts={texts} lang={lang} applicationId={data.id} record={r} latest={i === 0} />
                  ))}
                </ul>
              )}
              {s.mayRecord && editing?.stage.code !== s.code && (
                <div className="form-actions start">
                  <Button variant={s.records.length === 0 ? 'primary' : 'ghost'}
                    onClick={() => setEditing({ stage: s, correcting: s.records[0] ?? null })}>
                    {s.records.length === 0 ? k.record : k.correct}
                  </Button>
                </div>
              )}
              {editing?.stage.code === s.code && (
                <StageForm texts={texts} applicationId={data.id} stage={s} correcting={editing.correcting} caseDeposit={data.caseDeposit ?? null}
                  onSaved={saved} onCancel={() => setEditing(null)} onFailure={setFailure} />
              )}
            </li>
          ))}
        </ol>
        <ObjectionsSection texts={texts} lang={lang} data={data} onSaved={saved} />
        <ReconsiderationSection texts={texts} lang={lang} data={data} onSaved={saved} />
      </div>
    </Card>
  )
}

function RecordItem({ texts, lang, applicationId, record, latest }: {
  texts: Texts; lang: Lang; applicationId: string; record: StageRecord; latest: boolean
}) {
  const k = texts.procedures
  const d = record.details
  const names = (list: unknown) => Array.isArray(list) ? (list as Person[]).map((p) => `${p.name} (${p.capacity})`).join('; ') : ''
  return (
    <li data-record={record.id} data-outcome={record.outcome} data-latest={latest}>
      <Badge tone={record.outcome === 'COMPLETED' ? 'success' : 'warning'}>{k.outcomes[record.outcome]}</Badge>{' '}
      <span className="small">{record.recordedBySelf ? k.recordedBySelf : fill(k.recordedBy, { name: record.recordedByName })}</span>
      {' · '}<span className="small num">{new Date(record.recordedAt).toLocaleString(lang)}</span>
      {!latest && <span className="small muted"> · {k.superseded}</span>}
      <div className="small">{record.finding}</div>
      {typeof d.visitedOn === 'string' && (
        <div className="small muted">{fill(k.detailsVisit, { date: d.visitedOn, members: names(d.members) })}</div>
      )}
      {typeof d.reportReference === 'string' && (
        <div className="small muted">{fill(k.detailsReport, { reference: d.reportReference, date: String(d.reportDate), signatories: names(d.signatories) })}</div>
      )}
      {typeof d.objectionPeriod === 'string' && (
        <div className="small muted">{fill(k.detailsPublication, { period: d.objectionPeriod, locality: String(d.locality) })}</div>
      )}
      {typeof d.titleNumber === 'string' && typeof d.versionNo !== 'undefined' && (
        <div className="small muted" data-title-verified>
          {fill(k.detailsTitle, { number: d.titleNumber, version: String(d.versionNo),
            holders: Array.isArray(d.holders) ? (d.holders as { name: string; transferor: string | null }[])
              .map((h) => `${h.name} → ${h.transferor ?? k.noTransferor}`).join('; ') : '' })}
        </div>
      )}
      {typeof d.receiptReference === 'string' && (
        <div className="small muted" data-receipt>{fill(k.detailsReceipt, { reference: d.receiptReference })}</div>
      )}
      {typeof d.parcelReference === 'string' && typeof d.parcelVersionNo !== 'undefined' && (
        <div className="small muted">{fill(k.detailsCadastral, { parcel: d.parcelReference, version: String(d.parcelVersionNo) })}</div>
      )}
      {record.correctionReason && <div className="small">{fill(k.corrects, { reason: record.correctionReason })}</div>}
      {record.files.length > 0 && (
        <ul className="plain-list">
          {record.files.map((f) => <li key={f.id}><a href={proceduresApi.fileUrl(applicationId, f.id)}>{f.originalName}</a></li>)}
        </ul>
      )}
    </li>
  )
}

function StageForm({ texts, applicationId, stage, correcting, caseDeposit, onSaved, onCancel, onFailure }: {
  texts: Texts; applicationId: string; stage: Stage; correcting: StageRecord | null; caseDeposit: CaseProcedure['caseDeposit'] | null
  onSaved: (d: CaseProcedure) => void; onCancel: () => void; onFailure: (message: string | null) => void
}) {
  const k = texts.procedures
  const [outcome, setOutcome] = useState<Outcome>('COMPLETED')
  const [finding, setFinding] = useState('')
  const [reason, setReason] = useState('')
  const [date, setDate] = useState(() => new Date().toISOString().slice(0, 10))
  const [listed, setListed] = useState('')
  const [reference, setReference] = useState('')
  const [receipt, setReceipt] = useState(() => caseDeposit?.receipts[0]?.id ?? '')
  const [files, setFiles] = useState<File[]>([])
  const [tried, setTried] = useState(false)
  const [busy, setBusy] = useState(false)

  const findingError = tried && finding.trim().length < FINDING_MIN ? k.findingTooShort : null
  const reasonError = tried && correcting && reason.trim().length < REASON_MIN ? k.reasonTooShort : null
  const listedPeople = people(listed)
  const peopleError = tried && (stage.code === 'FIELD_VISIT' || stage.code === 'COMMISSION_REPORT') && !listedPeople ? k.membersRequired : null

  const submit = async () => {
    setTried(true)
    if (finding.trim().length < FINDING_MIN || (correcting && reason.trim().length < REASON_MIN)) return
    let details: Record<string, unknown> = {}
    if (stage.code === 'FIELD_VISIT') {
      if (!listedPeople) return
      details = { visitedOn: date, members: listedPeople }
    } else if (stage.code === 'COMMISSION_REPORT') {
      if (!listedPeople) return
      details = { reportReference: reference.trim(), reportDate: date, signatories: listedPeople }
    } else if (stage.code === 'DUTIES_EVIDENCE') {
      // D24: the receipt deposited with the deed, cited; SNIGF computes and collects no duty.
      details = { ...(receipt ? { receiptDocument: receipt } : {}), ...(reference.trim() ? { receiptReference: reference.trim() } : {}) }
    }
    const record: NewRecord = { outcome, finding: finding.trim(), details }
    if (correcting) {
      record.supersedes = correcting.id
      record.correctionReason = reason.trim()
    }
    setBusy(true)
    onFailure(null)
    try {
      onSaved(await proceduresApi.record(applicationId, stage.code, record, files))
    } catch (e) {
      onFailure(errorMessage(texts, e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="stack" data-stage-form={stage.code} onSubmit={(e) => { e.preventDefault(); void submit() }}>
      {stage.code === 'CADASTRAL_REVIEW' && <p className="small muted">{k.cadastralHint}</p>}
      {stage.code === 'PUBLICATION' && <p className="small muted">{k.publicationHint}</p>}
      {stage.code === 'TITLE_VERIFICATION' && <p className="small muted">{k.titleVerificationHint}</p>}
      {(stage.code === 'TITLE_VERIFICATION' || stage.code === 'DUTIES_EVIDENCE') && (
        caseDeposit
          ? <p className="small" data-case-deposit>{fill(k.caseDepositLine, { number: caseDeposit.titleNumber, status: caseDeposit.status })}</p>
          : <p className="small muted" data-case-deposit="none">{k.noCaseDeposit}</p>
      )}
      {stage.code === 'DUTIES_EVIDENCE' && <>
        <p className="small muted">{k.dutiesHint}</p>
        <Field label={k.receiptDocument}>
          <select value={receipt} onChange={(e) => setReceipt(e.target.value)}>
            <option value="">{k.noReceipt}</option>
            {(caseDeposit?.receipts ?? []).map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
          </select>
        </Field>
        <Field label={k.receiptReference}>
          <input maxLength={60} value={reference} onChange={(e) => setReference(e.target.value)} />
        </Field>
      </>}
      {stage.code !== 'PUBLICATION' && (
        <Field label={k.outcome}>
          <select value={outcome} onChange={(e) => setOutcome(e.target.value as Outcome)}>
            <option value="COMPLETED">{k.outcomes.COMPLETED}</option>
            <option value="NOT_SATISFIED">{k.outcomes.NOT_SATISFIED}</option>
          </select>
        </Field>
      )}
      <Field label={k.finding} hint={stage.code === 'PUBLICATION' ? k.publicationFindingHint : k.findingHint} error={findingError}>
        <textarea rows={3} maxLength={FINDING_MAX} value={finding} onChange={(e) => setFinding(e.target.value)} />
      </Field>
      {stage.code === 'COMMISSION_REPORT' && (
        <Field label={k.reportReference}>
          <input maxLength={60} value={reference} onChange={(e) => setReference(e.target.value)} />
        </Field>
      )}
      {(stage.code === 'FIELD_VISIT' || stage.code === 'COMMISSION_REPORT') && <>
        <Field label={stage.code === 'FIELD_VISIT' ? k.visitedOn : k.reportDate}>
          <input type="date" value={date} onChange={(e) => setDate(e.target.value)} />
        </Field>
        <Field label={stage.code === 'FIELD_VISIT' ? k.members : k.signatories}
          hint={stage.code === 'FIELD_VISIT' ? k.membersHint : k.signatoriesHint} error={peopleError}>
          <textarea rows={3} value={listed} onChange={(e) => setListed(e.target.value)} />
        </Field>
      </>}
      <Field label={k.files} hint={stage.code === 'COMMISSION_REPORT' ? k.reportFilesHint : undefined}>
        <input type="file" multiple accept="application/pdf,image/jpeg,image/png"
          onChange={(e) => setFiles(Array.from(e.target.files ?? []).slice(0, MAX_FILES))} />
      </Field>
      {correcting && (
        <Field label={k.correctionReason} hint={k.correctionReasonHint} error={reasonError}>
          <textarea rows={2} maxLength={REASON_MAX} value={reason} onChange={(e) => setReason(e.target.value)} />
        </Field>
      )}
      <div className="form-actions start">
        <Button type="submit" disabled={busy}>{k.save}</Button>
        <Button variant="ghost" disabled={busy} onClick={onCancel}>{k.cancel}</Button>
      </div>
    </form>
  )
}
