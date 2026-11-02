// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The second administrator's decision on a request: confirmed in a dialog to approve, with a reason
// to reject. The database refuses the requester and the verifier whatever the screen offers.
import { fill } from '../../shared/i18n'
import type { Texts } from '../../shared/i18n'
import type { PageContext } from '../../shared/session/types'
import { useDialog } from '../../shared/ui/dialogContext'
import { professionalApi, REASON_MAX, REASON_MIN } from './api'
import type { ChangeRequest } from './api'

export function decisionDialogs(texts: Texts, r: ChangeRequest) {
  const p = texts.professionals
  const kind = p.requestKinds[r.kind]
  return {
    approve: { title: fill(p.approveTitle, { kind }), message: fill(p.approveText, { name: r.professionalName }), confirmLabel: p.approve, cancelLabel: texts.common.cancel },
    reject: {
      title: fill(p.rejectTitle, { kind }), message: fill(p.rejectText, { name: r.professionalName }), label: p.reason, hint: p.reasonHint,
      minLength: REASON_MIN, maxLength: REASON_MAX, tooShort: p.reasonTooShort, confirmLabel: p.reject, cancelLabel: texts.common.cancel, tone: 'danger' as const,
    },
  }
}

/** Approves or rejects a request through its dialog, then calls back. */
export function useDecision(ctx: PageContext, onDone: () => Promise<void>) {
  const dialog = useDialog()
  const { texts, run } = ctx
  return {
    approve: async (r: ChangeRequest) => {
      if (!(await dialog.confirm(decisionDialogs(texts, r).approve))) return
      await run(async () => { await professionalApi.approve(r.id); await onDone() })
    },
    reject: async (r: ChangeRequest) => {
      const reason = await dialog.reason(decisionDialogs(texts, r).reject)
      if (reason === null) return
      await run(async () => { await professionalApi.reject(r.id, reason.trim()); await onDone() })
    },
  }
}
