// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Confirmations and reasons, in place of window.confirm and window.prompt: the consequence
// is stated, a required reason is checked as it is typed, and the native <dialog> gives
// focus trapping, Escape and an inert page behind. Used only where the consequence justifies
// the extra step (decisions, revocations, spending a special grant, removing a key).
//
//   const dialog = useDialog()
//   const reason = await dialog.reason({ title, message, label, minLength: 10, confirmLabel, cancelLabel })
//   if (reason === null) return   // cancelled
import { useCallback, useEffect, useId, useRef, useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import { Button } from './Button'
import { DialogContext } from './dialogContext'
import type { Common, ReasonOptions, Request } from './dialogContext'
import { Icon } from './Icon'

export function DialogProvider({ children }: { children: ReactNode }) {
  const [request, setRequest] = useState<Request | null>(null)

  const confirm = useCallback((options: Common) => new Promise<boolean>((resolve) => {
    setRequest({ options, withReason: false, resolve: (v) => resolve(v === true) })
  }), [])
  const reason = useCallback((options: ReasonOptions) => new Promise<string | null>((resolve) => {
    setRequest({ options, withReason: true, resolve: (v) => resolve(typeof v === 'string' ? v : null) })
  }), [])

  const close = (value: string | boolean | null) => {
    request?.resolve(value)
    setRequest(null)
  }

  return (
    <DialogContext.Provider value={{ confirm, reason }}>
      {children}
      {request && <DialogView key={request.options.title} request={request} onClose={close} />}
    </DialogContext.Provider>
  )
}

function DialogView({ request, onClose }: { request: Request; onClose: (value: string | boolean | null) => void }) {
  const { options, withReason } = request
  const ref = useRef<HTMLDialogElement>(null)
  const id = useId()
  const [text, setText] = useState('')
  const [touched, setTouched] = useState(false)

  useEffect(() => {
    const dialog = ref.current
    if (!dialog) return
    if (typeof dialog.showModal === 'function') dialog.showModal()
    else dialog.setAttribute('open', '')
  }, [])

  const min = options.minLength ?? 1
  const tooShort = withReason && text.trim().length < min
  const error = withReason && touched && tooShort
    ? (options.tooShort ?? '').replace('{n}', String(min)) || undefined
    : undefined

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (withReason) {
      setTouched(true)
      if (tooShort) return
      onClose(text.trim())
    } else {
      onClose(true)
    }
  }

  return (
    <dialog ref={ref} className="dialog" aria-labelledby={`${id}-title`} aria-describedby={options.message ? `${id}-message` : undefined}
      onCancel={(e) => { e.preventDefault(); onClose(withReason ? null : false) }}>
      <form onSubmit={submit} noValidate>
        <header className="dialog-header">
          {options.tone === 'danger' && <span className="dialog-icon danger"><Icon name="alert" /></span>}
          <h2 id={`${id}-title`}>{options.title}</h2>
        </header>
        {options.message && <div id={`${id}-message`} className="dialog-message">{options.message}</div>}
        {withReason && (
          <div className="field">
            <label htmlFor={`${id}-reason`} className="field-label">{options.label}<span className="field-required" aria-hidden="true"> *</span></label>
            <textarea id={`${id}-reason`} rows={3} autoFocus maxLength={options.maxLength ?? 500} value={text}
              aria-invalid={error ? true : undefined} aria-describedby={[options.hint ? `${id}-hint` : '', error ? `${id}-error` : ''].join(' ').trim() || undefined}
              onChange={(e) => setText(e.target.value)} onBlur={() => setTouched(true)} />
            {options.hint && <p id={`${id}-hint`} className="field-hint">{options.hint}</p>}
            {error && <p id={`${id}-error`} className="field-error">{error}</p>}
          </div>
        )}
        <footer className="dialog-actions">
          <Button variant="secondary" onClick={() => onClose(withReason ? null : false)}>{options.cancelLabel}</Button>
          <Button type="submit" variant={options.tone === 'danger' ? 'danger' : 'primary'} autoFocus={!withReason}>
            {options.confirmLabel}
          </Button>
        </footer>
      </form>
    </dialog>
  )
}
