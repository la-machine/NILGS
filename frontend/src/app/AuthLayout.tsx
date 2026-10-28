// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The frame of the sign-in steps: the institution on one side (logo, purpose, the three
// commitments the system keeps), the current step on the other. On small screens the
// institution side shrinks to the logo above the form.
import type { ReactNode } from 'react'
import type { Lang, Texts } from '../shared/i18n'
import { Icon } from '../shared/ui/Icon'
import type { IconName } from '../shared/ui/Icon'
import { DemoBanner } from './DemoBanner'

export function AuthLayout({ texts, lang, onToggleLang, error, children }: {
  texts: Texts; lang: Lang; onToggleLang: () => void; error: ReactNode; children: ReactNode
}) {
  const points: [IconName, string, string][] = [
    ['lock', texts.auth.pointSecureTitle, texts.auth.pointSecure],
    ['audit', texts.auth.pointAuditTitle, texts.auth.pointAudit],
    ['parcel', texts.auth.pointPreciseTitle, texts.auth.pointPrecise],
  ]
  return (
    <div className="auth" lang={lang}>
      <DemoBanner />
      <div className="auth-body">
        <aside className="auth-brand">
          <img className="auth-logo" src="/brand/snigf-logo-480.png" width="200" height="202"
            alt="SNIGF — Système National Intégré de Gouvernance Foncière / National Integrated Land Governance System" />
          <p className="auth-tagline">{texts.auth.tagline}</p>
          <ul className="auth-points">
            {points.map(([icon, title, text]) => (
              <li key={title}>
                <span className="auth-point-icon"><Icon name={icon} /></span>
                <span><strong>{title}</strong>{text}</span>
              </li>
            ))}
          </ul>
        </aside>
        <main className="auth-main">
          <div className="auth-toolbar">
            <button type="button" className="btn btn-ghost btn-sm" onClick={onToggleLang}
              lang={lang === 'fr' ? 'en' : 'fr'}>
              <Icon name="globe" size={16} />
              {lang === 'fr' ? 'English' : 'Français'}
            </button>
          </div>
          <div className="auth-content">
            {error}
            {children}
          </div>
          <p className="auth-footer">{texts.auth.footer}</p>
        </main>
      </div>
    </div>
  )
}
