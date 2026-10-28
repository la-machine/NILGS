// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Sign-in with a single-use recovery code, when every key is lost.
import { useState } from 'react'
import type { FormEvent } from 'react'
import type { Texts } from '../../../shared/i18n'
import { Button } from '../../../shared/ui/Button'
import { Field } from '../../../shared/ui/Field'
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
      <form onSubmit={submit} className="form">
        <Field label={texts.code} hint={texts.auth.codeHint}>
          <input autoComplete="one-time-code" required maxLength={64} spellCheck={false} className="mono" value={code}
            onChange={(e) => setCode(e.target.value)} />
        </Field>
        <Button type="submit" variant="primary" busy={busy} className="btn-block">{texts.confirm}</Button>
      </form>
      <div className="auth-alt">
        <button type="button" className="link-button" onClick={onBack}>{texts.back}</button>
      </div>
    </Section>
  )
}
