// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The recovery codes, shown once at the end of enrolment; the person confirms they stored
// them before continuing.
import { useState } from 'react'
import type { Texts } from '../../../shared/i18n'
import { Section } from '../../../shared/ui/Section'

export function RecoveryCodesScreen({ texts, codes, onDone }: { texts: Texts; codes: string[]; onDone: () => void }) {
  const [stored, setStored] = useState(false)
  return (
    <Section title={texts.codesTitle} intro={texts.codesIntro}>
      <ol className="codes">{codes.map((code) => <li key={code}><code>{code}</code></li>)}</ol>
      <label className="check"><input type="checkbox" checked={stored} onChange={(e) => setStored(e.target.checked)} />
        {texts.codesStored}</label>
      <button type="button" disabled={!stored} onClick={onDone}>{texts.continue}</button>
    </Section>
  )
}
