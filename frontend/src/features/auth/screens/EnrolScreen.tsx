// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Staff enrolment: register the required number of authenticators (two, ADR-016), each
// with a label, before anything else is possible.
import { useState } from 'react'
import type { FormEvent } from 'react'
import { fill } from '../../../shared/i18n'
import type { Texts } from '../../../shared/i18n'
import { Section } from '../../../shared/ui/Section'
import { webAuthnAvailable } from '../webauthn'

export function EnrolScreen({ texts, busy, registered, required, onRegister, onSignOut }: {
  texts: Texts; busy: boolean; registered: number; required: number
  onRegister: (label: string) => void; onSignOut: () => void
}) {
  const [label, setLabel] = useState('')
  const submit = (e: FormEvent) => {
    e.preventDefault()
    onRegister(label)
    setLabel('')
  }
  return (
    <Section title={texts.enrolTitle} intro={fill(texts.enrolIntro, { required: required || 2 })}>
      {registered > 0 && <p className="progress">{fill(texts.enrolProgress, { registered, required })}</p>}
      {webAuthnAvailable() ? (
        <form onSubmit={submit}>
          <label>{texts.keyLabel}<input required maxLength={64} pattern="[\p{L}\p{N} ._'()\-]{1,64}" value={label}
            onChange={(e) => setLabel(e.target.value)} /></label>
          <button type="submit" disabled={busy}>{texts.registerKey}</button>
        </form>
      ) : <p className="error">{texts.noWebAuthn}</p>}
      <p><button type="button" className="link" onClick={onSignOut}>{texts.signOut}</button></p>
    </Section>
  )
}
