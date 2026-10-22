// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// First factor: e-mail and password. The password field is cleared after every attempt.
import { useState } from 'react'
import type { FormEvent } from 'react'
import type { Texts } from '../../../shared/i18n'
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
    <Section title={texts.title}>
      <form onSubmit={submit}>
        <label>{texts.email}<input type="email" autoComplete="username" required value={email}
          onChange={(e) => setEmail(e.target.value)} /></label>
        <label>{texts.password}<input type="password" autoComplete="current-password" required value={password}
          onChange={(e) => setPassword(e.target.value)} /></label>
        <button type="submit" disabled={busy}>{texts.signIn}</button>
      </form>
      <p><button type="button" className="link" onClick={onRegister}>{texts.noAccount}</button></p>
    </Section>
  )
}
