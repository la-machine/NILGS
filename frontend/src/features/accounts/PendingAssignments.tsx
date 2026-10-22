// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Role requests waiting for a second administrator (ADR-026). The requester and the
// beneficiary see "your request" instead of the decision buttons; the server refuses them
// anyway.
import type { Texts } from '../../shared/i18n'
import type { AssignmentRequest } from './api'

export function PendingAssignments({ texts, userId, requests, canApprove, onDecide }: {
  texts: Texts; userId: string; requests: AssignmentRequest[]; canApprove: boolean
  onDecide: (id: string, decision: 'approve' | 'reject') => void
}) {
  if (requests.length === 0) return null
  return (
    <>
      <h3>{texts.pendingAssignments}</h3>
      <ul className="requests">
        {requests.map((r) => (
          <li key={r.id} data-request={r.id}>
            <strong>{r.role} @ {r.officeCode}</strong> → <code>{r.userId}</code> — {r.reason}{' '}
            {r.requestedBy === userId || r.userId === userId
              ? <em>{texts.requestedByYou}</em>
              : canApprove && (
                <>
                  <button type="button" onClick={() => onDecide(r.id, 'approve')}>{texts.approve}</button>{' '}
                  <button type="button" className="link" onClick={() => onDecide(r.id, 'reject')}>{texts.reject}</button>
                </>
              )}
          </li>
        ))}
      </ul>
    </>
  )
}
