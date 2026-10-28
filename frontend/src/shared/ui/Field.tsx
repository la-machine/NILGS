// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// A form field: label, the control, a help line and the error, tied together for assistive
// technology (aria-describedby, aria-invalid). The control is the child; Field gives it the
// ids, so every input keeps its own props.
import { cloneElement, isValidElement, useId } from 'react'
import type { ReactElement, ReactNode } from 'react'

export function Field({ label, hint, error, required, requiredLabel, children, className }: {
  label: ReactNode; hint?: ReactNode; error?: string | null; required?: boolean; requiredLabel?: string
  children: ReactElement<Record<string, unknown>>; className?: string
}) {
  const id = useId()
  const hintId = hint ? `${id}-hint` : undefined
  const errorId = error ? `${id}-error` : undefined
  const describedBy = [hintId, errorId].filter(Boolean).join(' ') || undefined
  const control = isValidElement(children)
    ? cloneElement(children, {
      id,
      'aria-describedby': describedBy,
      'aria-invalid': error ? true : undefined,
      required: required ?? children.props.required,
    })
    : children
  return (
    <div className={['field', className ?? ''].filter(Boolean).join(' ')}>
      <label htmlFor={id} className="field-label">
        {label}
        {required && <span className="field-required" aria-hidden="true"> *</span>}
        {required && requiredLabel && <span className="visually-hidden"> ({requiredLabel})</span>}
      </label>
      {control}
      {hint && <p id={hintId} className="field-hint">{hint}</p>}
      {error && <p id={errorId} className="field-error">{error}</p>}
    </div>
  )
}
