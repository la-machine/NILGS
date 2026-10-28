// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Sign-in, first factor. The password is cleared after each attempt; the server's answer
// decides what comes next (second factor, enrolment, or the application).
import { useState } from 'react'
import type { FormEvent } from 'react'
import type { Texts } from '../../../shared/i18n'
import { Button } from '../../../shared/ui/Button'
import { Field } from '../../../shared/ui/Field'
import { Section } from '../../../shared/ui/Section'

export function LoginScreen({ texts, busy, onSubmit, onRegister }: {
  texts: Texts; busy: boolean; onSubmit: (email: string, password: string) => void; onRegister: () => void
}) {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const submit = (e: FormEvent) => {
    e.preventDefault()
    onSubmit(email, password)
    setPassword('')
  }
  return (
    <Section title={texts.title} intro={texts.auth.signInIntro}>
      <form onSubmit={submit} className="form">
        <Field label={texts.email}>
          <input type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </Field>
        <Field label={texts.password}>
          <input type="password" autoComplete="current-password" required value={password}
            onChange={(e) => setPassword(e.target.value)} />
        </Field>
        <Button type="submit" variant="primary" busy={busy} className="btn-block">{texts.signIn}</Button>
      </form>
      <div className="auth-alt">
        <span>{texts.auth.applicantQuestion}</span>
        <button type="button" className="link-button" onClick={onRegister}>{texts.noAccount}</button>
      </div>
    </Section>
  )
}
