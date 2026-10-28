// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Feedback: alerts (success, information, warning, error), badges, the spinner, skeleton
// placeholders and empty states. Status is never told by colour alone: each carries an icon
// and words.
import type { ReactNode } from 'react'
import { Icon } from './Icon'
import type { IconName } from './Icon'

export type Tone = 'success' | 'info' | 'warning' | 'danger' | 'neutral'

const TONE_ICON: Record<Tone, IconName> = {
  success: 'checkCircle', info: 'info', warning: 'alert', danger: 'xCircle', neutral: 'info',
}

/** A message block. Errors are announced at once (role alert), the rest politely (role status). */
export function Alert({ tone = 'info', title, children, action, onDismiss, dismissLabel }: {
  tone?: Tone; title?: string; children?: ReactNode; action?: ReactNode; onDismiss?: () => void; dismissLabel?: string
}) {
  return (
    <div className={`alert alert-${tone}`} role={tone === 'danger' ? 'alert' : 'status'}>
      <Icon name={TONE_ICON[tone]} className="alert-icon" />
      <div className="alert-body">
        {title && <p className="alert-title">{title}</p>}
        {children && <div className="alert-text">{children}</div>}
        {action && <div className="alert-action">{action}</div>}
      </div>
      {onDismiss && (
        <button type="button" className="alert-dismiss" onClick={onDismiss} aria-label={dismissLabel}>
          <Icon name="close" size={16} />
        </button>
      )}
    </div>
  )
}

export function Badge({ tone = 'neutral', icon, children }: { tone?: Tone; icon?: IconName; children: ReactNode }) {
  return (
    <span className={`badge badge-${tone}`}>
      {icon && <Icon name={icon} size={14} />}
      {children}
    </span>
  )
}

export function Spinner({ label }: { label?: string }) {
  return (
    <span className="spinner" role={label ? 'status' : undefined} aria-label={label} aria-hidden={label ? undefined : true} />
  )
}

/** Placeholder lines while content loads; hidden from assistive technology (the caller announces). */
export function Skeleton({ lines = 3 }: { lines?: number }) {
  return (
    <div className="skeleton" aria-hidden="true">
      {Array.from({ length: lines }, (_, i) => <span key={i} style={{ width: `${90 - (i % 3) * 18}%` }} />)}
    </div>
  )
}

export function EmptyState({ icon = 'info', title, children, action }: {
  icon?: IconName; title: string; children?: ReactNode; action?: ReactNode
}) {
  return (
    <div className="empty-state">
      <span className="empty-icon"><Icon name={icon} size={24} /></span>
      <p className="empty-title">{title}</p>
      {children && <div className="empty-text">{children}</div>}
      {action && <div className="empty-action">{action}</div>}
    </div>
  )
}
