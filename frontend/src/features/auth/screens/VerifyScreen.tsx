// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Second factor at each sign-in: prove one registered security key, or fall back to a
// recovery code.
import type { Texts } from '../../../shared/i18n'
import { Section } from '../../../shared/ui/Section'
import { webAuthnAvailable } from '../webauthn'

export function VerifyScreen({ texts, busy, onProve, onUseCode, onSignOut }: {
  texts: Texts; busy: boolean; onProve: () => void; onUseCode: () => void; onSignOut: () => void
}) {
  return (
    <Section title={texts.verifyTitle} intro={texts.verifyIntro}>
      <button type="button" disabled={busy || !webAuthnAvailable()} onClick={onProve}>{texts.useKey}</button>
      {!webAuthnAvailable() && <p className="error">{texts.noWebAuthn}</p>}
      <p><button type="button" className="link" onClick={onUseCode}>{texts.useCode}</button></p>
      <p><button type="button" className="link" onClick={onSignOut}>{texts.signOut}</button></p>
    </Section>
  )
}
