// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Self-registration of an applicant. The answer is the same whether the address was new or
// not (no account enumeration); staff accounts are never created here.
import { useState } from 'react'
import type { FormEvent } from 'react'
import type { Lang, Texts } from '../../../shared/i18n'
import { Button } from '../../../shared/ui/Button'
import { Field } from '../../../shared/ui/Field'
import { Section } from '../../../shared/ui/Section'
import type { Registration } from '../api'

export function RegisterScreen({ texts, busy, lang, onSubmit, onBack }: {
  texts: Texts; busy: boolean; lang: Lang; onSubmit: (body: Registration) => void; onBack: () => void
}) {
  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [password, setPassword] = useState('')
  const [preferredLanguage, setPreferredLanguage] = useState<string>(lang)
  const submit = (e: FormEvent) => {
    e.preventDefault()
    onSubmit({ email, displayName, password, preferredLanguage })
  }
  return (
    <Section title={texts.registerTitle} intro={texts.auth.registerIntro}>
      <form onSubmit={submit} className="form">
        <Field label={texts.email} required requiredLabel={texts.common.required}>
          <input type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} />
        </Field>
        <Field label={texts.displayName} required requiredLabel={texts.common.required}>
          <input autoComplete="name" maxLength={120} value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
        </Field>
        <Field label={texts.password} hint={texts.passwordHint} required requiredLabel={texts.common.required}>
          <input type="password" autoComplete="new-password" minLength={12} maxLength={128} value={password}
            onChange={(e) => setPassword(e.target.value)} />
        </Field>
        <Field label={texts.language}>
          <select value={preferredLanguage} onChange={(e) => setPreferredLanguage(e.target.value)}>
            <option value="fr">Français</option>
            <option value="en">English</option>
          </select>
        </Field>
        <Button type="submit" variant="primary" busy={busy} className="btn-block">{texts.create}</Button>
      </form>
      <div className="auth-alt">
        <button type="button" className="link-button" onClick={onBack}>{texts.haveAccount}</button>
      </div>
    </Section>
  )
}
