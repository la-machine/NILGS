// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// "Approved, to register" (ADR-042 §4): the approved cases of the conservation not registered yet. Each says what stops
// it and whom the title will name; a conservator who never handled the case proposes its CREATION (procedure A) or its
// TRANSFER onto the title the case names (procedure B, step 6), the holders taken by the database from the approved
// ownership statement (ADR-043). Two conservators then sign it: registering writes the register only, never a title
// document or a notice. The server and the database decide.
import { useState } from 'react'
import type { FormEvent } from 'react'
import { fill } from '../../shared/i18n'
import type { Lang, Texts } from '../../shared/i18n'
import { Button } from '../../shared/ui/Button'
import { Field } from '../../shared/ui/Field'
import { Alert, Badge } from '../../shared/ui/Feedback'
import { errorMessage } from '../../shared/ui/errorMessage'
import { REASON_MAX, REASON_MIN, registerApi } from './api'
import type { CaseToRegister } from './api'
import { CaseEvidence } from './CaseEvidence'

export function CaseToRegisterItem({ texts, lang, entry: c, onProposed }: {
  texts: Texts; lang: Lang; entry: CaseToRegister; onProposed: () => void
}) {
  const k = texts.register
  const [open, setOpen] = useState(false)
  const problems = c.problems.map((p) => (texts.errors as Record<string, string>)[p] ?? p)
  return (
    <li className="exception-item" data-case-to-register={c.reference}>
      <div className="exception-head">
        {c.inscriptionId
          ? <Badge tone="warning" icon="signature">{k.caseProposed}</Badge>
          : c.mayPropose ? <Badge tone="success">{k.caseReady}</Badge> : <Badge tone="neutral">{k.caseHeld}</Badge>}
        <span className="mono small">{c.reference}</span> · <span className="mono small">{c.parcelReference}</span>
        {c.registers === 'TRANSFER' && <Badge tone="info">{fill(k.caseTransferOf, { number: c.titleNumber ?? '' })}</Badge>}
      </div>
      <div className="muted small">{fill(k.caseApprovedOn, { date: new Date(c.decidedAt).toLocaleDateString(lang) })}</div>
      {c.owners && <div className="small" data-owners>{fill(k.caseOwners, { owners: c.owners })}</div>}
      <CaseEvidence texts={texts} lang={lang} applicationId={c.applicationId} />
      {problems.length > 0 && !c.inscriptionId && (
        <ul className="small" data-case-problems>{problems.map((p) => <li key={p}>{p}</li>)}</ul>
      )}
      {c.mayPropose && !c.inscriptionId && (
        open
          ? <CaseCreationForm texts={texts} applicationId={c.applicationId} owners={c.owners} transfer={c.registers === 'TRANSFER'}
              onDone={() => { setOpen(false); onProposed() }} onCancel={() => setOpen(false)} />
          : <div className="form-actions start">
              <Button icon="plus" onClick={() => setOpen(true)}>{c.registers === 'TRANSFER' ? k.proposeTransferFromCase : k.proposeFromCase}</Button>
            </div>
      )}
    </li>
  )
}

function CaseCreationForm({ texts, applicationId, owners, transfer, onDone, onCancel }: {
  texts: Texts; applicationId: string; owners: string | null; transfer: boolean; onDone: () => void; onCancel: () => void
}) {
  const k = texts.register
  const [reason, setReason] = useState('')
  const [tried, setTried] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const reasonError = tried && reason.trim().length < REASON_MIN ? k.reasonTooShort : null

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setTried(true)
    if (reason.trim().length < REASON_MIN) return
    setBusy(true)
    setFailure(null)
    try {
      await (transfer ? registerApi.proposeTransferFromCase : registerApi.proposeFromCase)(applicationId, reason.trim())
      onDone()
    } catch (error) {
      setFailure(errorMessage(texts, error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="form" onSubmit={(e) => void submit(e)} noValidate data-case-creation-form={transfer ? 'TRANSFER' : 'CREATION'}>
      <p className="muted small">{transfer ? k.caseTransferIntro : k.caseCreationIntro}</p>
      {owners && <p data-case-owners>{fill(k.caseOwners, { owners })}</p>}
      <Field label={k.reason} hint={k.reasonHint} error={reasonError}>
        <textarea rows={2} maxLength={REASON_MAX} value={reason} onChange={(e) => setReason(e.target.value)} />
      </Field>
      {failure && <Alert tone="danger">{failure}</Alert>}
      <div className="form-actions start">
        <Button type="submit" variant="primary" busy={busy} disabled={busy}>{k.proposeCreation}</Button>
        <Button variant="ghost" onClick={onCancel}>{texts.common.cancel}</Button>
      </div>
    </form>
  )
}
