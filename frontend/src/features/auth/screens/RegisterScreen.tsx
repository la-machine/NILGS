// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Applicant self-registration. The answer never says whether the address already existed.
import { useState } from 'react'
import type { FormEvent } from 'react'
import type { Lang, Texts } from '../../../shared/i18n'
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
    <Section title={texts.registerTitle}>
      <form onSubmit={submit}>
        <label>{texts.email}<input type="email" autoComplete="email" required value={email}
          onChange={(e) => setEmail(e.target.value)} /></label>
        <label>{texts.displayName}<input autoComplete="name" required maxLength={120} value={displayName}
          onChange={(e) => setDisplayName(e.target.value)} /></label>
        <label>{texts.password}<input type="password" autoComplete="new-password" required minLength={12} maxLength={128}
          value={password} onChange={(e) => setPassword(e.target.value)} /></label>
        <p className="hint">{texts.passwordHint}</p>
        <label>{texts.language}<select value={preferredLanguage} onChange={(e) => setPreferredLanguage(e.target.value)}>
          <option value="fr">Français</option>
          <option value="en">English</option>
        </select></label>
        <button type="submit" disabled={busy}>{texts.create}</button>
      </form>
      <p><button type="button" className="link" onClick={onBack}>{texts.haveAccount}</button></p>
    </Section>
  )
}
