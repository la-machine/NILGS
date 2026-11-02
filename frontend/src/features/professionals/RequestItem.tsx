// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// A request for a second administrator: an activation opened by the verification, or a
// reinstatement, revocation or office change. Its requester never sees the buttons (the database
// refuses them anyway, and the verifier of an activation as well).
import type { Lang, Texts } from '../../shared/i18n'
import { Button } from '../../shared/ui/Button'
import { Badge } from '../../shared/ui/Feedback'
import { Icon } from '../../shared/ui/Icon'
import type { ChangeRequest } from './api'
import { REQUEST_TONE } from './tones'

export function RequestItem({ texts, lang, request: r, viewerId, canDecide, showName, onApprove, onReject }: {
  texts: Texts; lang: Lang; request: ChangeRequest; viewerId: string; canDecide: boolean; showName: boolean
  onApprove: () => void; onReject: () => void
}) {
  const p = texts.professionals
  const own = r.requestedBy === viewerId
  const pending = r.status === 'PENDING'
  return (
    <li className="request stacked" data-request={r.id} data-kind={r.kind} data-status={r.status}>
      <div className="request-main">
        <p className="request-title">
          <strong>{p.requestKinds[r.kind]}</strong>
          {showName && <> · {r.professionalName}</>}
          {' '}<Badge tone={REQUEST_TONE[r.status]}>{p.requestStatuses[r.status]}</Badge>
        </p>
        <p className="request-meta">
          <Icon name="user" size={14} /> {r.requestedByName} · <span className="num">{new Date(r.requestedAt).toLocaleString(lang)}</span>
          {r.officesAdd.length > 0 && <> · {p.officesAdd}: {r.officesAdd.join(', ')}</>}
          {r.officesRemove.length > 0 && <> · {p.officesRemove}: {r.officesRemove.join(', ')}</>}
        </p>
        {r.reason && <p className="request-reason">{r.reason}</p>}
        {r.decidedByName && (
          <p className="request-meta">{r.decidedByName} · <span className="num">{r.decidedAt && new Date(r.decidedAt).toLocaleString(lang)}</span>
            {r.decisionReason && <> · {r.decisionReason}</>}</p>
        )}
        {pending && own && <p className="small muted">{p.yourRequest}</p>}
      </div>
      {pending && canDecide && !own && (
        <div className="form-actions start">
          <Button variant="primary" icon="check" onClick={onApprove}>{p.approve}</Button>
          <Button variant="ghost" onClick={onReject}>{p.reject}</Button>
        </div>
      )}
    </li>
  )
}
