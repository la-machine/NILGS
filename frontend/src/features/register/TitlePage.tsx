// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// A title (#/register/titles/<id>): what the register says now, computed by the database from its
// registered inscriptions; the whole history, cancelled and corrected entries shown as such; its
// deposits and conflicts; a re-verification; the on-screen extract, which is not a certificate.
import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { fill } from '../../shared/i18n'
import type { Lang, Texts } from '../../shared/i18n'
import { href } from '../../shared/router/router'
import { canAt } from '../../shared/session/access'
import type { PageContext } from '../../shared/session/types'
import { Button } from '../../shared/ui/Button'
import { Card, PageHeader } from '../../shared/ui/Card'
import { useDialog } from '../../shared/ui/dialogContext'
import { Field } from '../../shared/ui/Field'
import { Alert, Badge, Skeleton } from '../../shared/ui/Feedback'
import { errorMessage } from '../../shared/ui/errorMessage'
import { CHARGE_KINDS, PROPOSABLE, REASON_MAX, REASON_MIN, references, registerApi, RESTRICTION_KINDS, share } from './api'
import type { Charge, Extract, InscriptionKind, Party, TitleView, Transaction, Verification } from './api'
import { DepositDocuments } from './DepositDocuments'
import { InscriptionFacts, ProposalItem } from './Proposals'
import { STATUS_TONE } from './tones'

export function TitlePage({ ctx, id }: { ctx: PageContext; id: string }) {
  const { texts, session, lang } = ctx
  const k = texts.register
  const [view, setView] = useState<TitleView | null>(null)
  const [parties, setParties] = useState<Party[]>([])
  const [extract, setExtract] = useState<Extract | null>(null)
  const [verification, setVerification] = useState<Verification | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [reloads, setReloads] = useState(0)
  const reload = () => { setExtract(null); setVerification(null); setReloads((n) => n + 1) }

  useEffect(() => {
    let current = true
    registerApi.title(id)
      .then(async (loaded) => {
        const known = await registerApi.parties(loaded.title.officeCode)
        if (current) {
          setView(loaded)
          setParties(known)
        }
      })
      .catch((e) => { if (current) setFailure(errorMessage(texts, e)) })
    return () => { current = false }
  }, [id, reloads]) // eslint-disable-line react-hooks/exhaustive-deps

  if (failure && !view) return <div className="page"><Alert tone="danger">{failure}</Alert></div>
  if (!view) return <div className="page" aria-busy="true"><Skeleton lines={8} /></div>
  const { title } = view
  const office = title.officeCode
  const canPropose = canAt(session, 'register.propose', office)
  const history = view.inscriptions.filter((i) => i.status === 'REGISTERED')
  const open = view.inscriptions.filter((i) => i.status === 'PROPOSED' || i.status === 'CONFIRMATION_PENDING')
  const closed = view.inscriptions.filter((i) => i.status === 'REJECTED' || i.status === 'WITHDRAWN')

  return (
    <div className="page" data-title-page={title.number} data-version={title.version} data-status={title.status}>
      <PageHeader title={title.number} description={k.titleIntro} breadcrumbLabel={texts.shell.breadcrumb}
        crumbs={[{ label: k.title, href: href(['register'], { office }) }, { label: title.number }]}
        actions={<a className="btn btn-secondary" href={href(['audit', 'timeline'], { type: 'LAND_TITLE', reference: title.number })}>{k.timeline}</a>} />
      <Alert tone="warning" title={k.demoTitle}>{k.demoText}</Alert>
      {failure && <Alert tone="danger">{failure}</Alert>}

      <Card title={k.stateTitle} icon="file" description={fill(k.stateIntro, { version: title.version })}>
        <dl className="properties">
          <div><dt>{k.status}</dt><dd><Badge tone={title.status === 'ACTIVE' ? 'success' : 'neutral'}>{k.titleStatuses[title.status]}</Badge></dd></div>
          <div><dt>{k.area}</dt><dd className="num">{title.areaM2 ?? '—'}</dd></div>
          {title.parcelReference && <div><dt>{k.parcel}</dt><dd><a className="mono" href={href(['parcels', title.parcelReference])}>{title.parcelReference}</a></dd></div>}
          <div data-holders><dt>{k.holders}</dt><dd>{title.holders.map((h) => `${h.partyName} (${(k.interests as Record<string, string>)[h.interest] ?? h.interest}, ${share(h)})`).join(' ; ')}</dd></div>
          <div><dt>{k.chargesTitle}</dt><dd>{chargeList(texts, title.charges)}</dd></div>
          <div><dt>{k.restrictionsTitle}</dt><dd>{chargeList(texts, title.restrictions)}</dd></div>
        </dl>
        <div className="form-actions start">
          <Button icon="file" onClick={async () => setExtract(await registerApi.extract(id))}>{k.showExtract}</Button>
          <Button icon="shield" onClick={async () => setVerification(await registerApi.verification(id))}>{k.verify}</Button>
        </div>
        {verification && (
          <div data-verification={verification.intact ? 'intact' : 'broken'}>
            <Alert tone={verification.intact ? 'success' : 'danger'} title={verification.intact ? k.verifyIntact : k.verifyBroken}>
              {fill(k.verifySummary, { inscriptions: verification.inscriptionsChecked, signatures: verification.signaturesChecked, versions: verification.versionsChecked })}
              {verification.problems.length > 0 && <ul>{verification.problems.map((p) => <li key={p}>{p}</li>)}</ul>}
            </Alert>
          </div>
        )}
        {extract && (
          <div className="not-a-certificate" data-extract>
            <Alert tone="danger" title={extract.label}>{k.extractText}</Alert>
            <dl className="properties">
              <div><dt>{k.number}</dt><dd className="mono">{extract.titleNumber}</dd></div>
              <div><dt>{k.holders}</dt><dd>{extract.holders.map((h) => `${h.partyName} (${share(h)})`).join(' ; ')}</dd></div>
              <div><dt>{k.chargesTitle}</dt><dd>{chargeList(texts, extract.charges)}</dd></div>
              <div><dt>{k.restrictionsTitle}</dt><dd>{chargeList(texts, extract.restrictions)}</dd></div>
            </dl>
          </div>
        )}
      </Card>

      {open.length > 0 && (
        <Card title={k.proposalsTitle} icon="signature" description={k.proposalsIntro}>
          <ul className="plain-list exception-list">
            {open.map((i) => (
              <ProposalItem key={`${i.id}-${i.status}`} texts={texts} lang={lang} viewerId={session.userId} inscription={i}
                canInscribe={canAt(session, 'register.inscribe', office)} canConfirm={canAt(session, 'register.confirm', office)}
                canPropose={canPropose} onChanged={reload} />
            ))}
          </ul>
        </Card>
      )}

      <Card title={k.historyTitle} icon="clock" description={k.historyIntro}>
        <ol className="plain-list exception-list">
          {history.map((i) => (
            <li key={i.id} className="exception-item" data-entry={i.number} data-kind={i.kind}>
              <div className="exception-head">
                <strong className="num">{fill(k.entry, { number: i.number ?? '' })}</strong>
                <span>{k.kinds[i.kind]}</span>
                {i.cancelledByNumber !== null && <Badge tone="danger">{fill(k.cancelledBy, { number: i.cancelledByNumber })}</Badge>}
                {i.correctedByNumber !== null && <Badge tone="warning">{fill(k.correctedBy, { number: i.correctedByNumber })}</Badge>}
                <span className="muted num small">{i.registeredByName}{i.confirmedByName ? ` · ${fill(k.confirmedBy, { name: i.confirmedByName })}` : ''} · {i.registeredAt && new Date(i.registeredAt).toLocaleString(lang)}</span>
              </div>
              <InscriptionFacts texts={texts} inscription={i} />
            </li>
          ))}
        </ol>
        {closed.length > 0 && (
          <details>
            <summary>{fill(k.closedProposals, { n: closed.length })}</summary>
            <ul className="plain-list exception-list">
              {closed.map((i) => (
                <li key={i.id} className="exception-item">
                  <Badge tone={STATUS_TONE[i.status]}>{k.statuses[i.status]}</Badge> {k.kinds[i.kind]} · <span className="small">{i.closedReason}</span>
                </li>
              ))}
            </ul>
          </details>
        )}
      </Card>

      <Card title={k.transactionsTitle} icon="lock" description={k.transactionsIntro}>
        {view.transactions.length === 0 ? <p className="muted">{k.noTransactions}</p> : (
          <ul className="plain-list exception-list">
            {view.transactions.map((t) => <TransactionItem key={`${t.id}-${t.status}`} texts={texts} lang={lang} transaction={t} canPropose={canPropose}
              canDecide={canAt(session, 'register.transaction.decide', office)} onChanged={reload} />)}
          </ul>
        )}
        {canPropose && title.status === 'ACTIVE' && <DepositForm texts={texts} titleId={id} parties={parties} onDone={reload} />}
      </Card>

      {canPropose && <ProposeForm texts={texts} view={view} parties={parties} onDone={reload} />}
    </div>
  )
}

function chargeList(texts: Texts, charges: Charge[]) {
  const k = texts.register
  if (charges.length === 0) return <span className="muted">{k.none}</span>
  return charges.map((c) => `${(k.chargeKinds as Record<string, string>)[c.kind] ?? c.kind}${c.beneficiaryName ? ` · ${c.beneficiaryName}` : ''}${c.blocksTransfer ? ` · ${k.blocksTransfer}` : ''}`).join(' ; ')
}

function TransactionItem({ texts, lang, transaction: t, canPropose, canDecide, onChanged }: {
  texts: Texts; lang: Lang; transaction: Transaction; canPropose: boolean; canDecide: boolean; onChanged: () => void
}) {
  const k = texts.register
  const dialog = useDialog()
  const [failure, setFailure] = useState<string | null>(null)
  const active = t.status === 'HOLDING' || t.status === 'IN_CONFLICT'
  const close = async (action: 'withdraw' | 'refuse') => {
    const reason = await dialog.reason({
      title: action === 'refuse' ? k.refuseTitle : k.withdrawDepositTitle, message: action === 'refuse' ? k.refuseText : k.withdrawDepositText,
      label: k.reason, hint: k.reasonHint, minLength: REASON_MIN, maxLength: REASON_MAX, tooShort: k.reasonTooShort,
      confirmLabel: action === 'refuse' ? k.refuse : k.withdrawDeposit, cancelLabel: texts.common.cancel, tone: 'danger',
    })
    if (reason === null) return
    try {
      await (action === 'refuse' ? registerApi.refuseDeposit : registerApi.withdrawDeposit)(t.id, reason.trim())
      onChanged()
    } catch (error) {
      setFailure(errorMessage(texts, error))
    }
  }
  return (
    <li className="exception-item" data-transaction={t.id} data-status={t.status}>
      <div className="exception-head">
        <Badge tone={t.status === 'HOLDING' ? 'warning' : t.status === 'IN_CONFLICT' ? 'danger' : 'neutral'}>{(k.transactionStatuses as Record<string, string>)[t.status] ?? t.status}</Badge>
        <span>{(k.depositKinds as Record<string, string>)[t.kind] ?? t.kind}</span>
        {t.overdue && <Badge tone="warning" icon="clock">{k.overdue}</Badge>}
      </div>
      <dl className="properties">
        <div><dt>{k.depositor}</dt><dd>{t.depositorName}</dd></div>
        {t.submittedByName && <div><dt>{k.submittedBy}</dt><dd>{t.submittedByName}</dd></div>}
        <div><dt>{k.receivedBy}</dt><dd>{t.receivedByName}</dd></div>
        <div><dt>{k.reviewDue}</dt><dd className="num">{new Date(t.reviewDueAt).toLocaleDateString()}</dd></div>
        {t.closedReason && <div><dt>{k.reason}</dt><dd>{t.closedReason}</dd></div>}
      </dl>
      <DepositDocuments texts={texts} lang={lang} transactionId={t.id} open={active} canAdd={canPropose} onChanged={onChanged} />
      {failure && <Alert tone="danger">{failure}</Alert>}
      {active && (
        <div className="form-actions start">
          {canPropose && <Button variant="ghost" onClick={() => close('withdraw')}>{k.withdrawDeposit}</Button>}
          {canDecide && <Button variant="danger" onClick={() => close('refuse')}>{k.refuse}</Button>}
        </div>
      )}
    </li>
  )
}

function DepositForm({ texts, titleId, parties, onDone }: { texts: Texts; titleId: string; parties: Party[]; onDone: () => void }) {
  const k = texts.register
  const [kind, setKind] = useState('SALE')
  const [depositor, setDepositor] = useState('')
  const [notary, setNotary] = useState('')
  const [evidence, setEvidence] = useState('')
  const [failure, setFailure] = useState<string | null>(null)
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setFailure(null)
    try {
      await registerApi.deposit({ titleId, kind, depositorPartyId: depositor, submittedByPartyId: notary || undefined, evidenceReferences: references(evidence) })
      setEvidence('')
      onDone()
    } catch (error) {
      setFailure(errorMessage(texts, error))
    }
  }
  return (
    <form className="form" onSubmit={submit} data-deposit-form>
      <h3>{k.depositTitle}</h3>
      <p className="small muted">{k.depositIntro}</p>
      {failure && <Alert tone="danger">{failure}</Alert>}
      <Field label={k.depositKind}>
        <select value={kind} onChange={(e) => setKind(e.target.value)}>
          {['SALE', 'GIFT', 'INHERITANCE'].map((o) => <option key={o} value={o}>{(k.depositKinds as Record<string, string>)[o]}</option>)}
        </select>
      </Field>
      <Field label={k.depositor} required requiredLabel={texts.common.required}>
        <select value={depositor} onChange={(e) => setDepositor(e.target.value)} required>
          <option value="" disabled>{k.choose}</option>
          {parties.filter((p) => p.kind !== 'NOTARY').map((p) => <option key={p.id} value={p.id}>{p.fullName}</option>)}
        </select>
      </Field>
      <Field label={k.submittedBy} hint={k.submittedByHint}>
        <select value={notary} onChange={(e) => setNotary(e.target.value)}>
          <option value="">{k.none}</option>
          {parties.filter((p) => p.kind === 'NOTARY').map((p) => <option key={p.id} value={p.id}>{p.fullName}</option>)}
        </select>
      </Field>
      <Field label={k.evidence} hint={k.evidenceHint} required requiredLabel={texts.common.required}><input value={evidence} onChange={(e) => setEvidence(e.target.value)} required /></Field>
      <div className="form-actions start"><Button type="submit" icon="plus">{k.recordDeposit}</Button></div>
    </form>
  )
}

const TARGETED: InscriptionKind[] = ['CHARGE_RELEASE', 'RESTRICTION_RELEASE', 'CANCELLATION', 'CORRECTION']

function ProposeForm({ texts, view, parties, onDone }: { texts: Texts; view: TitleView; parties: Party[]; onDone: () => void }) {
  const k = texts.register
  const { title } = view
  const [kind, setKind] = useState<InscriptionKind>('TRANSFER')
  const [target, setTarget] = useState('')
  const [holder, setHolder] = useState('')
  const [chargeKind, setChargeKind] = useState('')
  const [blocks, setBlocks] = useState(false)
  const [note, setNote] = useState('')
  const [reference, setReference] = useState('')
  const [reason, setReason] = useState('')
  const [evidence, setEvidence] = useState('')
  const [failure, setFailure] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const holding = view.transactions.find((t) => t.status === 'HOLDING')
  const targets = view.inscriptions.filter((i) => i.status === 'REGISTERED' && i.cancelledByNumber === null)
  const targetEntry = targets.find((i) => i.id === target)
  const carriesHolders = kind === 'TRANSFER' || (kind === 'CORRECTION' && (targetEntry?.kind === 'CREATION' || targetEntry?.kind === 'TRANSFER'))
  const carriesCharge = kind === 'CHARGE' || kind === 'RESTRICTION' || (kind === 'CORRECTION' && (targetEntry?.kind === 'CHARGE' || targetEntry?.kind === 'RESTRICTION'))
  const restriction = kind === 'RESTRICTION' || targetEntry?.kind === 'RESTRICTION'
  const chargeKinds = restriction ? RESTRICTION_KINDS : CHARGE_KINDS

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setFailure(null)
    try {
      await registerApi.propose({
        officeCode: title.officeCode, titleId: title.id, kind, expectedVersion: title.version, authorityType: 'SYNTHETIC_DEMO',
        authorityReference: reference, reason, evidenceReferences: references(evidence),
        completesTransactionId: kind === 'TRANSFER' ? holding?.id : undefined,
        targetInscriptionId: TARGETED.includes(kind) ? target : undefined,
        holders: carriesHolders ? [{ party: holder, interest: 'OWNERSHIP', shareNum: 1, shareDen: 1 }] : undefined,
        charge: carriesCharge ? { kind: chargeKind || chargeKinds[0], blocksTransfer: restriction && blocks, note: note || undefined } : undefined,
      })
      setReason('')
      setReference('')
      setEvidence('')
      onDone()
    } catch (error) {
      setFailure(errorMessage(texts, error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card title={k.proposeTitle2} icon="plus" description={fill(k.proposeIntro, { version: title.version })}>
      {failure && <Alert tone="danger">{failure}</Alert>}
      <form className="form" onSubmit={submit} data-propose-form>
        <Field label={k.kind}>
          <select value={kind} onChange={(e) => { setKind(e.target.value as InscriptionKind); setTarget('') }}>
            {PROPOSABLE.map((o) => <option key={o} value={o}>{k.kinds[o]}</option>)}
          </select>
        </Field>
        {TARGETED.includes(kind) && (
          <Field label={k.target} required requiredLabel={texts.common.required}>
            <select value={target} onChange={(e) => setTarget(e.target.value)} required>
              <option value="" disabled>{k.choose}</option>
              {targets.map((i) => <option key={i.id} value={i.id}>{fill(k.entry, { number: i.number ?? '' })} · {k.kinds[i.kind]}</option>)}
            </select>
          </Field>
        )}
        {kind === 'TRANSFER' && !holding && <Alert tone="info">{k.transferNeedsDeposit}</Alert>}
        {carriesHolders && (
          <Field label={k.newHolder} required requiredLabel={texts.common.required}>
            <select value={holder} onChange={(e) => setHolder(e.target.value)} required>
              <option value="" disabled>{k.choose}</option>
              {parties.filter((p) => p.kind !== 'NOTARY').map((p) => <option key={p.id} value={p.id}>{p.fullName}</option>)}
            </select>
          </Field>
        )}
        {carriesCharge && (
          <>
            <Field label={k.charge}>
              <select value={chargeKind || chargeKinds[0]} onChange={(e) => setChargeKind(e.target.value)}>
                {chargeKinds.map((o) => <option key={o} value={o}>{(k.chargeKinds as Record<string, string>)[o]}</option>)}
              </select>
            </Field>
            {restriction && <label className="checkbox"><input type="checkbox" checked={blocks} onChange={(e) => setBlocks(e.target.checked)} /> {k.blocksTransfer}</label>}
            <Field label={k.note}><input value={note} onChange={(e) => setNote(e.target.value)} /></Field>
          </>
        )}
        <Field label={k.authority} hint={k.demoNameHint} required requiredLabel={texts.common.required}><input value={reference} onChange={(e) => setReference(e.target.value)} required /></Field>
        <Field label={k.reason} hint={k.reasonHint} required requiredLabel={texts.common.required}><textarea rows={2} value={reason} maxLength={REASON_MAX} onChange={(e) => setReason(e.target.value)} required /></Field>
        <Field label={k.evidence} hint={k.evidenceHint} required requiredLabel={texts.common.required}><input value={evidence} onChange={(e) => setEvidence(e.target.value)} required /></Field>
        <div className="form-actions start"><Button type="submit" variant="primary" icon="plus" busy={busy} disabled={busy}>{k.propose}</Button></div>
      </form>
    </Card>
  )
}
