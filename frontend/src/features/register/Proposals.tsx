// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// A proposal and what this person may do with it. Proposed is not registered: it has no effect until
// it is signed, and until a second conservator confirms it where dual control applies. The buttons
// only offer what applies; the server and the database decide.
import { useState } from 'react'
import { fill } from '../../shared/i18n'
import type { Lang, Texts } from '../../shared/i18n'
import { Button } from '../../shared/ui/Button'
import { useDialog } from '../../shared/ui/dialogContext'
import { Alert, Badge } from '../../shared/ui/Feedback'
import { errorMessage } from '../../shared/ui/errorMessage'
import { DUAL_CONTROL, REASON_MAX, REASON_MIN, registerApi, share, signingActFor } from './api'
import type { Inscription } from './api'
import { STATUS_TONE } from './tones'

/** What an inscription says: its holders, its charge, its authority. */
export function InscriptionFacts({ texts, inscription: i }: { texts: Texts; inscription: Inscription }) {
  const k = texts.register
  return (
    <dl className="properties">
      <div><dt>{k.authority}</dt><dd>{(k.authorityTypes as Record<string, string>)[i.authorityType] ?? i.authorityType} · <span className="mono small">{i.authorityReference}</span></dd></div>
      <div><dt>{k.reason}</dt><dd>{i.reason}</dd></div>
      <div><dt>{k.evidence}</dt><dd className="mono small">{i.evidenceReferences.join(', ')}</dd></div>
      {i.holders.length > 0 && (
        <div><dt>{k.holders}</dt><dd>{i.holders.map((h) => `${h.partyName} (${(k.interests as Record<string, string>)[h.interest] ?? h.interest}, ${share(h)})`).join(' ; ')}</dd></div>
      )}
      {i.charge && (
        <div><dt>{k.charge}</dt><dd>{(k.chargeKinds as Record<string, string>)[i.charge.kind] ?? i.charge.kind}{i.charge.blocksTransfer ? ` · ${k.blocksTransfer}` : ''}{i.charge.note ? ` · ${i.charge.note}` : ''}</dd></div>
      )}
    </dl>
  )
}

export function ProposalItem({ texts, lang, viewerId, inscription: i, canInscribe, canConfirm, canPropose, onChanged }: {
  texts: Texts; lang: Lang; viewerId: string; inscription: Inscription; canInscribe: boolean; canConfirm: boolean; canPropose: boolean; onChanged: () => void
}) {
  const k = texts.register
  const dialog = useDialog()
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  const act = signingActFor(i, viewerId)
  const allowed = act === 'register' ? canInscribe : act === 'confirm' ? canConfirm : act === 'request-confirmation' ? canPropose : false

  const run = async (work: () => Promise<unknown>) => {
    setBusy(true)
    setFailure(null)
    try {
      await work()
      onChanged()
    } catch (error) {
      setFailure(errorMessage(texts, error))
    } finally {
      setBusy(false)
    }
  }

  const sign = async () => {
    if (!act) return
    const ok = await dialog.confirm({
      title: k.signTitle[act], message: fill(k.signText[act], { kind: k.kinds[i.kind] }),
      confirmLabel: k.signAction[act], cancelLabel: texts.common.cancel,
    })
    if (ok) await run(() => registerApi.sign(i.id, act))
  }

  const close = async (outcome: 'withdraw' | 'reject') => {
    const reason = await dialog.reason({
      title: k.closeTitle[outcome], message: k.closeText[outcome], label: k.reason, hint: k.reasonHint, minLength: REASON_MIN, maxLength: REASON_MAX,
      tooShort: k.reasonTooShort, confirmLabel: k.closeAction[outcome], cancelLabel: texts.common.cancel, tone: 'danger',
    })
    if (reason !== null) await run(() => (outcome === 'withdraw' ? registerApi.withdraw : registerApi.reject)(i.id, reason.trim()))
  }

  return (
    <li className="exception-item" data-inscription={i.id} data-kind={i.kind} data-status={i.status}>
      <div className="exception-head">
        <Badge tone={STATUS_TONE[i.status]}>{k.statuses[i.status]}</Badge>
        <strong>{k.kinds[i.kind]}</strong>
        {i.titleNumber && <span className="mono small">{i.titleNumber}</span>}
        <span className="muted num small">{i.proposedByName} · {new Date(i.proposedAt).toLocaleString(lang)}</span>
      </div>
      {DUAL_CONTROL.includes(i.kind) && <p className="small muted">{k.dualControl}</p>}
      <InscriptionFacts texts={texts} inscription={i} />
      {failure && <Alert tone="danger">{failure}</Alert>}
      <div className="form-actions start">
        {act && allowed && <Button variant="primary" icon="key" onClick={sign} busy={busy} disabled={busy}>{k.signAction[act]}</Button>}
        {i.proposedBy === viewerId && canPropose && <Button variant="ghost" onClick={() => close('withdraw')} disabled={busy}>{k.closeAction.withdraw}</Button>}
        {i.proposedBy !== viewerId && canInscribe && <Button variant="ghost" onClick={() => close('reject')} disabled={busy}>{k.closeAction.reject}</Button>}
      </div>
    </li>
  )
}
