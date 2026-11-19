// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// "Registered: DEMO-TF-…" (ADR-042 §4): the title an approved case was registered under in the DEMO register, and when;
// for a transfer (step 6), "Transfer registered" on the title the case named. A case is not a title, and this is not a
// title document or a certificate: it says so. Nothing before registration; never the holders.
import { fill } from '../../shared/i18n'
import type { Lang, Texts } from '../../shared/i18n'
import { Alert } from '../../shared/ui/Feedback'

export function RegistrationNotice({ texts, lang, registration }: {
  texts: Texts; lang: Lang; registration: { titleNumber: string; registeredAt: string; kind?: 'CREATION' | 'TRANSFER' } | null | undefined
}) {
  if (!registration) return null
  const a = texts.applications
  const transfer = registration.kind === 'TRANSFER'
  return (
    <Alert tone="success" title={fill(transfer ? a.transferRegisteredTitle : a.registeredTitle, { number: registration.titleNumber })}>
      <span data-registration={transfer ? 'TRANSFER' : 'CREATION'}>
        {fill(transfer ? a.transferRegisteredText : a.registeredText, { date: new Date(registration.registeredAt).toLocaleDateString(lang) })}
      </span>
    </Alert>
  )
}
