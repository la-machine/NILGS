// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Buttons. One primary action per area; secondary for the others; danger only where the
// consequence is irreversible or weakens someone's access; ghost for low-emphasis actions.
import type { ButtonHTMLAttributes, ReactNode } from 'react'
import { Icon } from './Icon'
import type { IconName } from './Icon'
import { Spinner } from './Feedback'

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger'

export function Button({ variant = 'secondary', size, icon, iconOnly, busy, children, className, type, ...rest }:
  ButtonHTMLAttributes<HTMLButtonElement> & {
    variant?: ButtonVariant; size?: 'sm'; icon?: IconName; iconOnly?: boolean; busy?: boolean; children?: ReactNode
  }) {
  const classes = ['btn', `btn-${variant}`, size ? `btn-${size}` : '', iconOnly ? 'btn-icon' : '', className ?? '']
    .filter(Boolean).join(' ')
  return (
    <button type={type ?? 'button'} className={classes} aria-busy={busy || undefined} {...rest}
      disabled={rest.disabled || busy}>
      {busy ? <Spinner /> : icon && <Icon name={icon} size={size === 'sm' ? 16 : 18} />}
      {iconOnly ? <span className="visually-hidden">{children}</span> : children}
    </button>
  )
}
