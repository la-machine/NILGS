// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Sign-in with a single-use recovery code when every key is lost (ADR-023).
import { useState } from 'react'
import type { FormEvent } from 'react'
import type { Texts } from '../../../shared/i18n'
import { Section } from '../../../shared/ui/Section'

export function RecoveryScreen({ texts, busy, onSubmit, onBack }: {
  texts: Texts; busy: boolean; onSubmit: (code: string) => void; onBack: () => void
}) {
  const [code, setCode] = useState('')
  const submit = (e: FormEvent) => {
    e.preventDefault()
    onSubmit(code)
    setCode('')
  }
  return (
    <Section title={texts.recoveryTitle} intro={texts.recoveryIntro}>
      <form onSubmit={submit}>
        <label>{texts.code}<input autoComplete="one-time-code" required maxLength={64} spellCheck={false} value={code}
          onChange={(e) => setCode(e.target.value)} /></label>
        <button type="submit" disabled={busy}>{texts.confirm}</button>
      </form>
      <p><button type="button" className="link" onClick={onBack}>{texts.back}</button></p>
    </Section>
  )
}
