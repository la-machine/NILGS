// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The frame of every screen: the demonstration notice (always visible, in both languages),
// the header with the logo and the language switch.
import type { ReactNode } from 'react'
import type { Lang } from '../shared/i18n'
import { BrandTitle } from '../shared/ui/Brand'

export function Layout({ lang, onToggleLang, children }: {
  lang: Lang; onToggleLang: () => void; children: ReactNode
}) {
  return (
    <>
      <div className="demo-banner" role="note">
        <span lang="fr">Démonstration — données fictives, aucune valeur juridique.</span>
        <span lang="en">Demonstration — synthetic data, no legal effect.</span>
      </div>
      <main lang={lang}>
        <header className="app-header">
          <BrandTitle />
          <button type="button" className="link" onClick={onToggleLang}>
            {lang === 'fr' ? 'English' : 'Français'}
          </button>
        </header>
        {children}
      </main>
    </>
  )
}
