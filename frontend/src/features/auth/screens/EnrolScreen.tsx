// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Enrolment of the second factor: staff register two different authenticators, one after
// the other, so losing one does not lock them out (ADR-023).
import { useState } from 'react'
import type { FormEvent } from 'react'
import { fill } from '../../../shared/i18n'
import type { Texts } from '../../../shared/i18n'
import { Button } from '../../../shared/ui/Button'
import { Field } from '../../../shared/ui/Field'
import { Alert } from '../../../shared/ui/Feedback'
import { Icon } from '../../../shared/ui/Icon'
import { Section } from '../../../shared/ui/Section'
import { webAuthnAvailable } from '../webauthn'

export function EnrolScreen({ texts, busy, registered, required, onRegister, onSignOut }: {
  texts: Texts; busy: boolean; registered: number; required: number
  onRegister: (label: string) => void; onSignOut: () => void
}) {
  const [label, setLabel] = useState('')
  const total = required || 2
  const submit = (e: FormEvent) => {
    e.preventDefault()
    onRegister(label)
    setLabel('')
  }
  return (
    <Section title={texts.enrolTitle} intro={fill(texts.enrolIntro, { required: total })}>
      <ol className="steps" aria-label={texts.auth.enrolSteps}>
        {Array.from({ length: total }, (_, i) => (
          <li key={i} className={i < registered ? 'done' : i === registered ? 'current' : ''}>
            <span className="step-dot">{i < registered ? <Icon name="check" size={14} /> : i + 1}</span>
            {fill(texts.auth.keyN, { n: i + 1 })}
          </li>
        ))}
      </ol>
      {registered > 0 && <p className="progress" role="status">{fill(texts.enrolProgress, { registered, required })}</p>}
      {webAuthnAvailable() ? (
        <form onSubmit={submit} className="form">
          <Field label={texts.keyLabel} hint={texts.auth.keyLabelHint}>
            <input required maxLength={64} pattern="[\p{L}\p{N} ._'\(\)\-]{1,64}" value={label}
              onChange={(e) => setLabel(e.target.value)} />
          </Field>
          <Button type="submit" variant="primary" icon="key" busy={busy} className="btn-block">{texts.registerKey}</Button>
        </form>
      ) : <Alert tone="danger">{texts.noWebAuthn}</Alert>}
      <div className="auth-alt">
        <button type="button" className="link-button" onClick={onSignOut}>{texts.signOut}</button>
      </div>
    </Section>
  )
}
