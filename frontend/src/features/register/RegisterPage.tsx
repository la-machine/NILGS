// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The conservation (#/register?office=): its titles, proposals waiting for a signature, conflicts,
// locks past their review date, and the two starting points: a party and a DEMO title.
import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { fill } from '../../shared/i18n'
import type { Texts } from '../../shared/i18n'
import { href } from '../../shared/router/router'
import { canAt, homeOffices, reach } from '../../shared/session/access'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { Card, PageHeader } from '../../shared/ui/Card'
import { useDialog } from '../../shared/ui/dialogContext'
import { Field } from '../../shared/ui/Field'
import { Alert, Badge, EmptyState, Skeleton } from '../../shared/ui/Feedback'
import { errorMessage } from '../../shared/ui/errorMessage'
import { CONFLICT_OUTCOMES, REASON_MAX, REASON_MIN, references, registerApi } from './api'
import type { Conflict, ConflictOutcome, Inscription, Party, TitleSummary, Transaction } from './api'
import { ProposalItem } from './Proposals'

interface Loaded { titles: TitleSummary[]; proposals: Inscription[]; conflicts: Conflict[]; overdue: Transaction[]; parties: Party[] }

export function RegisterPage({ ctx, params }: { ctx: PageContext; params: URLSearchParams }) {
  const { texts, session, lang } = ctx
  const k = texts.register
  const r = reach(session, 'register.read')
  const offices = r.everywhere ? homeOffices(session) : r.offices
  const office = (params.get('office') ?? offices[0] ?? '').toUpperCase()
  const [data, setData] = useState<Loaded | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [reloads, setReloads] = useState(0)
  const reload = () => setReloads((n) => n + 1)

  useEffect(() => {
    if (!office) return
    let current = true
    Promise.all([registerApi.titles(office), registerApi.proposals(office), registerApi.conflicts(office), registerApi.overdue(office), registerApi.parties(office)])
      .then(([titles, proposals, conflicts, overdue, parties]) => { if (current) setData({ titles, proposals, conflicts, overdue, parties }) })
      .catch((e) => { if (current) setFailure(errorMessage(texts, e)) })
    return () => { current = false }
  }, [office, reloads]) // eslint-disable-line react-hooks/exhaustive-deps

  const canPropose = canAt(session, 'register.propose', office)
  return (
    <div className="page" data-register-office={office}>
      <PageHeader title={k.title} description={k.intro} breadcrumbLabel={texts.shell.breadcrumb}
        actions={offices.length > 1 ? (
          <select aria-label={k.office} value={office} onChange={(e) => { window.location.hash = href(['register'], { office: e.target.value }) }}>
            {offices.map((o) => <option key={o} value={o}>{o}</option>)}
          </select>
        ) : undefined} />
      <Alert tone="warning" title={k.demoTitle}>{k.demoText}</Alert>
      {failure && <Alert tone="danger">{failure}</Alert>}
      {!data && !failure && <Skeleton lines={8} />}
      {data && (
        <>
          <Card title={k.titlesTitle} icon="file" description={k.titlesIntro}>
            {data.titles.length === 0 ? <EmptyState icon="file" title={k.noTitles}>{k.noTitlesHint}</EmptyState> : (
              <table className="table" data-titles>
                <thead><tr><th>{k.number}</th><th>{k.holders}</th><th>{k.status}</th><th>{k.version}</th><th>{k.lock}</th></tr></thead>
                <tbody>
                  {data.titles.map((t) => (
                    <tr key={t.id} data-title={t.number}>
                      <td><a className="mono" href={href(['register', 'titles', t.id])}>{t.number}</a></td>
                      <td>{t.holderNames}</td>
                      <td><Badge tone={t.status === 'ACTIVE' ? 'success' : 'neutral'}>{(k.titleStatuses as Record<string, string>)[t.status] ?? t.status}</Badge></td>
                      <td className="num">{t.version}</td>
                      <td>
                        {t.inConflict && <Badge tone="danger" icon="alert">{k.inConflict}</Badge>}{' '}
                        {t.locked && <Badge tone="warning" icon="lock">{k.locked}</Badge>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Card>
          <Card title={k.proposalsTitle} icon="signature" description={k.proposalsIntro}>
            {data.proposals.length === 0 ? <EmptyState icon="checkCircle" title={k.noProposals} /> : (
              <ul className="plain-list exception-list">
                {data.proposals.map((i) => (
                  <ProposalItem key={`${i.id}-${i.status}`} texts={texts} lang={lang} viewerId={session.userId} inscription={i}
                    canInscribe={canAt(session, 'register.inscribe', office)} canConfirm={canAt(session, 'register.confirm', office)}
                    canPropose={canPropose} onChanged={reload} />
                ))}
              </ul>
            )}
          </Card>
          <Card title={k.conflictsTitle} icon="alert" description={k.conflictsIntro}>
            {data.conflicts.length === 0 ? <EmptyState icon="checkCircle" title={k.noConflicts} /> : (
              <ul className="plain-list exception-list">
                {data.conflicts.map((c) => <ConflictItem key={c.id} texts={texts} conflict={c} canDecide={canAt(session, 'register.transaction.decide', office)} onChanged={reload} />)}
              </ul>
            )}
          </Card>
          <Card title={k.overdueTitle} icon="clock" description={k.overdueIntro}>
            {data.overdue.length === 0 ? <EmptyState icon="checkCircle" title={k.noOverdue} /> : (
              <ul className="plain-list exception-list">
                {data.overdue.map((t) => <OverdueItem key={t.id} texts={texts} transaction={t} canDecide={canAt(session, 'register.transaction.decide', office)} onChanged={reload} />)}
              </ul>
            )}
          </Card>
          {canPropose && (
            <>
              <PartyForm texts={texts} office={office} onCreated={reload} />
              <NewTitleForm texts={texts} office={office} parties={data.parties} onCreated={reload} />
            </>
          )}
        </>
      )}
    </div>
  )
}

function ConflictItem({ texts, conflict: c, canDecide, onChanged }: { texts: Texts; conflict: Conflict; canDecide: boolean; onChanged: () => void }) {
  const k = texts.register
  const dialog = useDialog()
  const [outcome, setOutcome] = useState<ConflictOutcome>('KEEP_HOLDING')
  const [evidence, setEvidence] = useState('')
  const [failure, setFailure] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const resolve = async () => {
    const reason = await dialog.reason({
      title: k.resolveTitle, message: k.outcomeText[outcome], label: k.reason, hint: k.reasonHint, minLength: REASON_MIN, maxLength: REASON_MAX,
      tooShort: k.reasonTooShort, confirmLabel: k.resolve, cancelLabel: texts.common.cancel,
    })
    if (reason === null) return
    setBusy(true)
    setFailure(null)
    try {
      await registerApi.resolve(c.id, { outcome, reason: reason.trim(), evidenceReferences: references(evidence) })
      onChanged()
    } catch (error) {
      setFailure(errorMessage(texts, error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <li className="exception-item" data-conflict={c.id}>
      <div className="exception-head">
        <Badge tone="danger" icon="alert">{k.inConflict}</Badge>
        <a className="mono small" href={href(['register', 'titles', c.titleId])}>{c.titleNumber}</a>
      </div>
      <p className="small">{fill(k.conflictSummary, { holding: c.holdingDepositor, arriving: c.arrivingDepositor })}</p>
      {failure && <Alert tone="danger">{failure}</Alert>}
      {canDecide && (
        <div className="form form-inline">
          <Field label={k.outcome}>
            <select value={outcome} onChange={(e) => setOutcome(e.target.value as ConflictOutcome)}>
              {CONFLICT_OUTCOMES.map((o) => <option key={o} value={o}>{k.outcomes[o]}</option>)}
            </select>
          </Field>
          <Field label={k.evidence} hint={k.evidenceHint}><input value={evidence} onChange={(e) => setEvidence(e.target.value)} /></Field>
          <div className="form-actions start"><Button icon="checkCircle" onClick={resolve} busy={busy} disabled={busy}>{k.resolve}</Button></div>
        </div>
      )}
    </li>
  )
}

function OverdueItem({ texts, transaction: t, canDecide, onChanged }: { texts: Texts; transaction: Transaction; canDecide: boolean; onChanged: () => void }) {
  const k = texts.register
  const dialog = useDialog()
  const [failure, setFailure] = useState<string | null>(null)
  const refuse = async () => {
    const reason = await dialog.reason({
      title: k.refuseTitle, message: k.refuseText, label: k.reason, hint: k.reasonHint, minLength: REASON_MIN, maxLength: REASON_MAX,
      tooShort: k.reasonTooShort, confirmLabel: k.refuse, cancelLabel: texts.common.cancel, tone: 'danger',
    })
    if (reason === null) return
    try {
      await registerApi.refuseDeposit(t.id, reason.trim())
      onChanged()
    } catch (error) {
      setFailure(errorMessage(texts, error))
    }
  }
  return (
    <li className="exception-item" data-overdue={t.id}>
      <div className="exception-head">
        <Badge tone="warning" icon="clock">{k.overdue}</Badge>
        <a className="mono small" href={href(['register', 'titles', t.titleId])}>{t.titleNumber}</a>
      </div>
      <p className="small">{fill(k.overdueSummary, { depositor: t.depositorName, date: new Date(t.reviewDueAt).toLocaleDateString() })}</p>
      {failure && <Alert tone="danger">{failure}</Alert>}
      {canDecide && <div className="form-actions start"><Button variant="danger" onClick={refuse}>{k.refuse}</Button></div>}
    </li>
  )
}

function PartyForm({ texts, office, onCreated }: { texts: Texts; office: string; onCreated: () => void }) {
  const k = texts.register
  const [kind, setKind] = useState<Party['kind']>('NATURAL_PERSON')
  const [fullName, setFullName] = useState('')
  const [identityNumber, setIdentityNumber] = useState('')
  const [chamber, setChamber] = useState('')
  const [registrationNumber, setRegistrationNumber] = useState('')
  const [jurisdiction, setJurisdiction] = useState('')
  const [failure, setFailure] = useState<string | null>(null)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setFailure(null)
    try {
      await registerApi.createParty({
        officeCode: office, kind, fullName, identityKind: 'NATIONAL_ID', identityNumber,
        ...(kind === 'NOTARY' ? { chamber, registrationNumber, jurisdiction } : {}),
      })
      setFullName('')
      setIdentityNumber('')
      onCreated()
    } catch (error) {
      setFailure(errorMessage(texts, error))
    }
  }

  return (
    <Card title={k.partyTitle} icon="users" description={k.partyIntro}>
      {failure && <Alert tone="danger">{failure}</Alert>}
      <form className="form" onSubmit={submit} data-party-form>
        <Field label={k.partyKind}>
          <select value={kind} onChange={(e) => setKind(e.target.value as Party['kind'])}>
            {(['NATURAL_PERSON', 'LEGAL_PERSON', 'NOTARY'] as const).map((o) => <option key={o} value={o}>{k.partyKinds[o]}</option>)}
          </select>
        </Field>
        <Field label={k.partyName} hint={k.demoNameHint} required requiredLabel={texts.common.required}><input value={fullName} onChange={(e) => setFullName(e.target.value)} required /></Field>
        <Field label={k.identityNumber} hint={k.demoNameHint} required requiredLabel={texts.common.required}><input value={identityNumber} onChange={(e) => setIdentityNumber(e.target.value)} required /></Field>
        {kind === 'NOTARY' && (
          <>
            <Field label={k.chamber} required requiredLabel={texts.common.required}><input value={chamber} onChange={(e) => setChamber(e.target.value)} required /></Field>
            <Field label={k.registrationNumber} required requiredLabel={texts.common.required}><input value={registrationNumber} onChange={(e) => setRegistrationNumber(e.target.value)} required /></Field>
            <Field label={k.jurisdiction} required requiredLabel={texts.common.required}><input value={jurisdiction} onChange={(e) => setJurisdiction(e.target.value)} required /></Field>
          </>
        )}
        <div className="form-actions start"><Button type="submit" icon="plus">{k.addParty}</Button></div>
      </form>
    </Card>
  )
}

function NewTitleForm({ texts, office, parties, onCreated }: { texts: Texts; office: string; parties: Party[]; onCreated: () => void }) {
  const k = texts.register
  const holders = parties.filter((p) => p.kind !== 'NOTARY')
  const [parcel, setParcel] = useState('')
  const [holder, setHolder] = useState('')
  const [reason, setReason] = useState('')
  const [evidence, setEvidence] = useState('')
  const [failure, setFailure] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setFailure(null)
    try {
      const version = await registerApi.parcelVersion(office, parcel.trim())
      await registerApi.propose({
        officeCode: office, kind: 'CREATION', expectedVersion: 0, authorityType: 'SYNTHETIC_DEMO', authorityReference: `DEMO-SEED-${parcel.trim()}`,
        reason, evidenceReferences: references(evidence), areaM2: version.areaM2, parcelVersionId: version.versionId,
        holders: [{ party: holder || holders[0]?.id, interest: 'OWNERSHIP', shareNum: 1, shareDen: 1 }],
      })
      setParcel('')
      setReason('')
      setEvidence('')
      onCreated()
    } catch (error) {
      setFailure(errorMessage(texts, error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card title={k.newTitleTitle} icon="plus" description={k.newTitleIntro}>
      {failure && <Alert tone="danger">{failure}</Alert>}
      <form className="form" onSubmit={submit} data-new-title-form>
        <Field label={k.parcel} required requiredLabel={texts.common.required}><input className="mono" value={parcel} onChange={(e) => setParcel(e.target.value)} required /></Field>
        <Field label={k.holder} required requiredLabel={texts.common.required}>
          <select value={holder} onChange={(e) => setHolder(e.target.value)} required>
            <option value="" disabled>{k.choose}</option>
            {holders.map((p) => <option key={p.id} value={p.id}>{p.fullName}</option>)}
          </select>
        </Field>
        <Field label={k.reason} hint={k.reasonHint} required requiredLabel={texts.common.required}><textarea rows={2} value={reason} maxLength={REASON_MAX} onChange={(e) => setReason(e.target.value)} required /></Field>
        <Field label={k.evidence} hint={k.evidenceHint} required requiredLabel={texts.common.required}><input value={evidence} onChange={(e) => setEvidence(e.target.value)} required /></Field>
        <div className="form-actions start"><Button type="submit" variant="primary" icon="plus" busy={busy} disabled={busy}>{k.proposeTitle}</Button></div>
      </form>
    </Card>
  )
}
