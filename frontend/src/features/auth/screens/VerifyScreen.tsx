// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Second factor at sign-in: prove one registered key, or fall back to a recovery code.
import type { Texts } from '../../../shared/i18n'
import { Button } from '../../../shared/ui/Button'
import { Alert } from '../../../shared/ui/Feedback'
import { Section } from '../../../shared/ui/Section'
import { webAuthnAvailable } from '../webauthn'

export function VerifyScreen({ texts, busy, onProve, onUseCode, onSignOut }: {
  texts: Texts; busy: boolean; onProve: () => void; onUseCode: () => void; onSignOut: () => void
}) {
  return (
    <Section title={texts.verifyTitle} intro={texts.verifyIntro}>
      <div className="form">
        <Button variant="primary" icon="key" busy={busy} disabled={!webAuthnAvailable()} onClick={onProve} className="btn-block">
          {texts.useKey}
        </Button>
        {!webAuthnAvailable() && <Alert tone="danger">{texts.noWebAuthn}</Alert>}
      </div>
      <div className="auth-alt auth-alt-stack">
        <button type="button" className="link-button" onClick={onUseCode}>{texts.useCode}</button>
        <button type="button" className="link-button" onClick={onSignOut}>{texts.signOut}</button>
      </div>
    </Section>
  )
}
