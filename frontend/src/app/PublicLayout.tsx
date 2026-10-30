// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The frame of a page anyone may open without signing in (the verification of an SNIGF
// document, ADR-039): the demonstration notice, the logo, the language switch and a way to
// sign in. No session is read or needed.
import type { ReactNode } from 'react'
import type { Lang, Texts } from '../shared/i18n'
import { href } from '../shared/router/router'
import { Icon } from '../shared/ui/Icon'
import { DemoBanner } from './DemoBanner'

export function PublicLayout({ texts, lang, onToggleLang, children }: {
  texts: Texts; lang: Lang; onToggleLang: () => void; children: ReactNode
}) {
  return (
    <div className="public" lang={lang}>
      <DemoBanner />
      <header className="public-bar">
        <img className="public-logo" src="/brand/snigf-logo-480.png" width="40" height="40" alt="SNIGF" />
        <span className="public-name">SNIGF</span>
        <span className="public-spacer" />
        <button type="button" className="btn btn-ghost btn-sm" onClick={onToggleLang} lang={lang === 'fr' ? 'en' : 'fr'}>
          <Icon name="globe" size={16} />
          {lang === 'fr' ? 'English' : 'Français'}
        </button>
        <a className="btn btn-secondary btn-sm" href={href(['dashboard'])}>{texts.signIn}</a>
      </header>
      <main className="public-main">{children}</main>
    </div>
  )
}
