// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The signed-in session: roles, authentication level, special grants in force with their
// limits, adding an authenticator, signing out.
import { fill } from '../../../shared/i18n'
import type { Texts } from '../../../shared/i18n'
import type { Session } from '../../../shared/session/types'
import { Section } from '../../../shared/ui/Section'
import { webAuthnAvailable } from '../webauthn'

export function SessionScreen({ texts, busy, session, notice, onSignOut, onAddKey }: {
  texts: Texts; busy: boolean; session: Session; notice?: string; onSignOut: () => void; onAddKey: () => void
}) {
  return (
    <Section title={`${texts.homeTitle} — ${session.displayName}`}>
      {notice && <p className="notice" role="status">{notice}</p>}
      <dl>
        <dt>{texts.roles}</dt><dd>{session.roles.join(', ') || '—'}</dd>
        <dt>{texts.level}</dt><dd>{session.authLevel === 'FULL' ? texts.levelFull : texts.levelPassword}</dd>
        {session.specialGrants.length > 0 && (
          <>
            <dt>{texts.specialGrantsMine}</dt>
            {session.specialGrants.map((g) => (
              <dd key={g.id}>
                <code>{g.permission}</code> @ {g.officeCode ?? texts.everywhere}
                {g.validUntil && <> — {fill(texts.until, { date: new Date(g.validUntil).toLocaleString() })}</>}
                {' — '}{g.usesLeft === null ? texts.unlimited : fill(texts.usesLeft, { n: g.usesLeft })}
              </dd>
            ))}
          </>
        )}
      </dl>
      {session.accountType === 'STAFF' && session.authLevel === 'FULL' && webAuthnAvailable() && (
        <p><button type="button" disabled={busy} onClick={onAddKey}>{texts.addKey}</button></p>
      )}
      <button type="button" disabled={busy} onClick={onSignOut}>{texts.signOut}</button>
    </Section>
  )
}
