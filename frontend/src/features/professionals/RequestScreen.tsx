// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The public request of a professional account (ADR-041 §3), #/professional-request. The answer is
// the same whether the address was new or not; the account can then sign in and enrol its keys,
// and holds no business permission until two administrators have verified and approved it.
import { useState } from 'react'
import type { FormEvent } from 'react'
import type { Lang, Texts } from '../../shared/i18n'
import { Button } from '../../shared/ui/Button'
import { Field } from '../../shared/ui/Field'
import { Section } from '../../shared/ui/Section'
import { PROFESSIONS } from './api'
import type { NewRequest, Profession } from './api'

export function RequestScreen({ texts, busy, lang, onSubmit, onBack }: {
  texts: Texts; busy: boolean; lang: Lang; onSubmit: (body: NewRequest) => void; onBack: () => void
}) {
  const p = texts.professionals
  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [password, setPassword] = useState('')
  const [preferredLanguage, setPreferredLanguage] = useState<string>(lang)
  const [profession, setProfession] = useState<Profession>('NOTARY')
  const [chamber, setChamber] = useState('')
  const [registrationNumber, setRegistrationNumber] = useState('')
  const [jurisdiction, setJurisdiction] = useState('')
  const submit = (e: FormEvent) => {
    e.preventDefault()
    onSubmit({ email, displayName, password, preferredLanguage, profession, chamber, registrationNumber, jurisdiction })
  }
  const required = { required: true, requiredLabel: texts.common.required }
  return (
    <Section title={p.requestTitle} intro={p.requestIntro}>
      <form onSubmit={submit} className="form" data-professional-request>
        <Field label={p.profession} {...required}>
          <select value={profession} onChange={(e) => setProfession(e.target.value as Profession)}>
            {PROFESSIONS.map((o) => <option key={o} value={o}>{p.professions[o]}</option>)}
          </select>
        </Field>
        <Field label={texts.email} {...required}>
          <input type="email" autoComplete="email" maxLength={254} value={email} onChange={(e) => setEmail(e.target.value)} />
        </Field>
        <Field label={texts.displayName} {...required}>
          <input autoComplete="name" maxLength={120} value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
        </Field>
        <Field label={texts.password} hint={texts.passwordHint} {...required}>
          <input type="password" autoComplete="new-password" minLength={12} maxLength={128} value={password} onChange={(e) => setPassword(e.target.value)} />
        </Field>
        <Field label={p.chamber} {...required}>
          <input maxLength={200} value={chamber} onChange={(e) => setChamber(e.target.value)} />
        </Field>
        <Field label={p.registrationNumber} hint={p.demoHint} {...required}>
          <input maxLength={64} value={registrationNumber} onChange={(e) => setRegistrationNumber(e.target.value)} />
        </Field>
        <Field label={p.jurisdiction} {...required}>
          <input maxLength={200} value={jurisdiction} onChange={(e) => setJurisdiction(e.target.value)} />
        </Field>
        <Field label={texts.language}>
          <select value={preferredLanguage} onChange={(e) => setPreferredLanguage(e.target.value)}>
            <option value="fr">Français</option>
            <option value="en">English</option>
          </select>
        </Field>
        <Button type="submit" variant="primary" busy={busy} className="btn-block">{p.requestSubmit}</Button>
      </form>
      <div className="auth-alt">
        <button type="button" className="link-button" onClick={onBack}>{texts.haveAccount}</button>
      </div>
    </Section>
  )
}
