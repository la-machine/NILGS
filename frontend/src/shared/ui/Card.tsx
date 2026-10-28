// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Cards and page headers: the two framing pieces of every page.
import type { ReactNode } from 'react'
import { Icon } from './Icon'
import type { IconName } from './Icon'

/** A titled surface. `as="section"` gives it a landmark named by its title. */
export function Card({ title, description, icon, actions, children, className, padded = true, id }: {
  title?: string; description?: ReactNode; icon?: IconName; actions?: ReactNode; children?: ReactNode
  className?: string; padded?: boolean; id?: string
}) {
  const headingId = id ? `${id}-title` : undefined
  return (
    <section className={['card', className ?? ''].filter(Boolean).join(' ')} aria-labelledby={headingId} id={id}>
      {(title || actions) && (
        <header className="card-header">
          <div className="card-heading">
            {icon && <span className="card-icon"><Icon name={icon} size={18} /></span>}
            <div>
              {title && <h2 id={headingId} className="card-title">{title}</h2>}
              {description && <p className="card-description">{description}</p>}
            </div>
          </div>
          {actions && <div className="card-actions">{actions}</div>}
        </header>
      )}
      <div className={padded ? 'card-body' : 'card-body flush'}>{children}</div>
    </section>
  )
}

export interface Crumb { label: string; href?: string }

/** The top of a page: breadcrumbs, title, one line of context, page actions. */
export function PageHeader({ title, description, crumbs, actions, meta, breadcrumbLabel }: {
  title: ReactNode; description?: ReactNode; crumbs?: Crumb[]; actions?: ReactNode; meta?: ReactNode; breadcrumbLabel: string
}) {
  return (
    <header className="page-header">
      {crumbs && crumbs.length > 0 && (
        <nav aria-label={breadcrumbLabel} className="breadcrumbs">
          <ol>
            {crumbs.map((c, i) => (
              <li key={`${c.label}-${i}`}>
                {c.href && i < crumbs.length - 1 ? <a href={c.href}>{c.label}</a> : <span aria-current="page">{c.label}</span>}
                {i < crumbs.length - 1 && <Icon name="chevronRight" size={14} />}
              </li>
            ))}
          </ol>
        </nav>
      )}
      <div className="page-header-row">
        <div className="page-header-text">
          <h1>{title}</h1>
          {description && <p className="page-description">{description}</p>}
          {meta && <div className="page-meta">{meta}</div>}
        </div>
        {actions && <div className="page-actions">{actions}</div>}
      </div>
    </header>
  )
}
