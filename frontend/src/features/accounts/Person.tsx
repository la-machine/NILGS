// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// A person named by their account id: their name when the directory has shown them, "you"
// for the signed-in person, otherwise a shortened id (the full id on hover and for screen
// readers). Never a guessed name.
import type { Texts } from '../../shared/i18n'
import type { Account } from './api'

export function Person({ id, known, texts, selfId }: {
  id: string; known: Record<string, Account>; texts: Texts; selfId: string
}) {
  if (id === selfId) return <strong>{texts.common.you}</strong>
  const account = known[id]
  if (account) return <span title={account.email}>{account.displayName}</span>
  return <code title={id} aria-label={id}>{id.slice(0, 8)}…</code>
}
