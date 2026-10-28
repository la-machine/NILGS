// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The ten recovery codes, shown once after enrolment. The person must say they stored them
// before going on; they are never shown again.
import { useState } from 'react'
import type { Texts } from '../../../shared/i18n'
import { Button } from '../../../shared/ui/Button'
import { Alert } from '../../../shared/ui/Feedback'
import { Section } from '../../../shared/ui/Section'

export function RecoveryCodesScreen({ texts, codes, onDone }: { texts: Texts; codes: string[]; onDone: () => void }) {
  const [stored, setStored] = useState(false)
  return (
    <Section title={texts.codesTitle}>
      <Alert tone="warning">{texts.codesIntro}</Alert>
      <ol className="codes">{codes.map((code) => <li key={code}><code>{code}</code></li>)}</ol>
      <label className="check">
        <input type="checkbox" checked={stored} onChange={(e) => setStored(e.target.checked)} />
        {texts.codesStored}
      </label>
      <Button variant="primary" disabled={!stored} onClick={onDone} className="btn-block">{texts.continue}</Button>
    </Section>
  )
}
