// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The signed-in frame: the demonstration notice, a sidebar of the places this session can
// use, a top bar (menu on small screens, language, the account menu), and the page. On
// screens narrower than 64rem the sidebar becomes a drawer.
import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import type { Lang, Texts } from '../shared/i18n'
import { href } from '../shared/router/router'
import type { Session } from '../shared/session/types'
import { Alert } from '../shared/ui/Feedback'
import { Icon } from '../shared/ui/Icon'
import { DemoBanner } from './DemoBanner'
import { ACCOUNT_PLACE, GROUPS } from './navigation'
import type { PlaceId } from './navigation'

export function AppShell({ texts, lang, session, active, busy, error, onDismissError, onToggleLang, onSignOut, onRefresh, extraVisible, children }: {
  texts: Texts; lang: Lang; session: Session; active: PlaceId | null; busy: boolean; error: string | null
  onDismissError: () => void; onToggleLang: () => void; onSignOut: () => void
  /** Choosing the place already shown reads the session again. */
  onRefresh: () => void
  /** Places kept visible although no longer granted (the audit page after a grant's last use). */
  extraVisible: PlaceId[]
  children: ReactNode
}) {
  const [drawerOpen, setDrawerOpen] = useState(false)
  // A new page closes the drawer.
  const [lastActive, setLastActive] = useState(active)
  if (lastActive !== active) {
    setLastActive(active)
    setDrawerOpen(false)
  }

  return (
    <div className="shell" lang={lang}>
      <a className="skip-link" href="#main-content" onClick={(e) => { e.preventDefault(); document.getElementById('main-content')?.focus() }}>
        {texts.shell.skipToContent}
      </a>
      <DemoBanner />
      <div className="shell-body">
        <aside className={drawerOpen ? 'sidebar open' : 'sidebar'} aria-label={texts.shell.navigation}>
          <div className="sidebar-brand">
            <img src="/brand/snigf-emblem-96.png" width="36" height="36" alt="" />
            <div>
              <span className="sidebar-brand-name">SNIGF</span>
              <span className="sidebar-brand-sub">{texts.shell.tagline}</span>
            </div>
            <button type="button" className="sidebar-close" onClick={() => setDrawerOpen(false)} aria-label={texts.shell.closeMenu}>
              <Icon name="close" />
            </button>
          </div>
          <nav className="sidebar-nav">
            {GROUPS.map((group, i) => {
              const places = group.places.filter((p) => p.visible(session) || extraVisible.includes(p.id))
              if (places.length === 0) return null
              const label = group.label(texts)
              return (
                <div key={i} className="nav-group">
                  {label && <p className="nav-group-label">{label}</p>}
                  <ul>
                    {places.map((p) => (
                      <li key={p.id}>
                        <a href={href([p.id])} className="nav-link" aria-current={active === p.id ? 'page' : undefined}
                          onClick={() => { if (active === p.id) onRefresh() }}>
                          <Icon name={p.icon} />
                          <span>{p.label(texts)}</span>
                        </a>
                      </li>
                    ))}
                  </ul>
                </div>
              )
            })}
          </nav>
          <div className="sidebar-footer">
            <a href={href([ACCOUNT_PLACE.id])} className="nav-link" aria-current={active === 'account' ? 'page' : undefined}
              onClick={() => { if (active === 'account') onRefresh() }}>
              <Icon name={ACCOUNT_PLACE.icon} />
              <span>{ACCOUNT_PLACE.label(texts)}</span>
            </a>
          </div>
        </aside>
        {drawerOpen && <div className="drawer-scrim" onClick={() => setDrawerOpen(false)} aria-hidden="true" />}

        <div className="shell-main">
          <header className="topbar">
            <button type="button" className="topbar-menu btn btn-ghost btn-icon" onClick={() => setDrawerOpen(true)}
              aria-expanded={drawerOpen} aria-label={texts.shell.openMenu}>
              <Icon name="menu" />
            </button>
            <OfficeContext texts={texts} session={session} />
            <div className="topbar-right">
              <button type="button" className="btn btn-ghost btn-sm" onClick={onToggleLang} lang={lang === 'fr' ? 'en' : 'fr'}>
                <Icon name="globe" size={16} />
                {lang === 'fr' ? 'English' : 'Français'}
              </button>
              <UserMenu texts={texts} session={session} onSignOut={onSignOut} />
            </div>
          </header>
          {busy && <div className="progress-bar" aria-hidden="true" />}
          <main id="main-content" className="content" tabIndex={-1} aria-busy={busy || undefined}>
            {error && (
              <div className="content-alert">
                <Alert tone="danger" onDismiss={onDismissError} dismissLabel={texts.shell.dismiss}>{error}</Alert>
              </div>
            )}
            {children}
          </main>
        </div>
      </div>
    </div>
  )
}

/** The offices this person works at: context for every office-scoped page. */
function OfficeContext({ texts, session }: { texts: Texts; session: Session }) {
  const offices = Array.from(new Set(session.grants.map((g) => g.officeCode).filter((o): o is string => !!o))).sort()
  if (offices.length === 0) return <span className="topbar-context" />
  return (
    <span className="topbar-context">
      <Icon name="office" size={16} />
      <span className="visually-hidden">{texts.shell.yourOffices}: </span>
      {offices.join(' · ')}
    </span>
  )
}

function UserMenu({ texts, session, onSignOut }: { texts: Texts; session: Session; onSignOut: () => void }) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const outside = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) setOpen(false) }
    const escape = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('mousedown', outside)
    document.addEventListener('keydown', escape)
    return () => {
      document.removeEventListener('mousedown', outside)
      document.removeEventListener('keydown', escape)
    }
  }, [open])
  const initials = session.displayName.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]?.toUpperCase()).join('')
  const mainRole = session.roles.find((r) => r !== 'APPLICANT') ?? session.roles[0]
  return (
    <div className="user-menu" ref={ref}>
      <button type="button" className="user-menu-button" aria-expanded={open} aria-controls="user-menu-list"
        onClick={() => setOpen(!open)}>
        <span className="avatar" aria-hidden="true">{initials || '?'}</span>
        <span className="user-menu-text">
          <span className="user-menu-name">{session.displayName}</span>
          {mainRole && <span className="user-menu-role">{texts.roleNames[mainRole as keyof Texts['roleNames']] ?? mainRole}</span>}
        </span>
        <Icon name="chevronDown" size={16} />
      </button>
      {open && (
        <div className="menu" id="user-menu-list">
          <a href={href(['account'])} className="menu-item" onClick={() => setOpen(false)}>
            <Icon name="user" size={16} />{texts.nav.account}
          </a>
          <button type="button" className="menu-item" onClick={() => { setOpen(false); onSignOut() }}>
            <Icon name="logout" size={16} />{texts.signOut}
          </button>
        </div>
      )}
    </div>
  )
}
