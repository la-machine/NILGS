// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The restricted lookup and the deposit (ADR-041 §6 and §7). The lookup answers a number at an
// office the professional serves, and nothing else. The deposit is one request: the kind, the
// client and every file, a deed among them; any refusal records nothing and locks nothing. A retry
// of the same request keeps its Idempotency-Key, so a double click never deposits twice.
import { useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError } from '../../shared/api/client'
import { fill } from '../../shared/i18n'
import type { Texts } from '../../shared/i18n'
import { Button } from '../../shared/ui/Button'
import { useDialog } from '../../shared/ui/dialogContext'
import { Field } from '../../shared/ui/Field'
import { Alert, Badge } from '../../shared/ui/Feedback'
import { DEPOSIT_KINDS, DEPOSITOR_KINDS, depositProblems, depositsApi, DOCUMENT_CATEGORIES, IDENTITY_KINDS, MAX_FILES } from './api'
import type { DepositFile, NewDepositor, TitleLookup } from './api'
import { refusal } from './tones'

export function TitleLookupForm({ texts, onFound }: { texts: Texts; onFound: (title: TitleLookup | null) => void }) {
  const p = texts.professionals
  const [number, setNumber] = useState('')
  const [notFound, setNotFound] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setFailure(null)
    setNotFound(false)
    onFound(null)
    try {
      onFound(await depositsApi.lookup(number.trim()))
    } catch (error) {
      if (error instanceof ApiError && error.code === 'TITLE_NOT_FOUND') setNotFound(true)
      else setFailure(refusal(texts, error))
    } finally {
      setBusy(false)
    }
  }
  return (
    <form className="form form-inline" onSubmit={submit} data-title-lookup>
      <Field label={p.titleNumber} required requiredLabel={texts.common.required}>
        <input className="mono" maxLength={64} value={number} onChange={(e) => setNumber(e.target.value)} />
      </Field>
      <div className="form-actions start"><Button type="submit" icon="search" busy={busy} disabled={busy || !number.trim()}>{p.lookup}</Button></div>
      {notFound && <Alert tone="info">{p.notFound}</Alert>}
      {failure && <Alert tone="danger">{failure}</Alert>}
    </form>
  )
}

/** The answer of the lookup, in full: number, office, and whether it accepts a deposit. */
export function LookupResult({ texts, title }: { texts: Texts; title: TitleLookup }) {
  const p = texts.professionals
  return (
    <dl className="properties" data-lookup-result={title.number}>
      <div><dt>{p.title}</dt><dd className="mono">{title.number}</dd></div>
      <div><dt>{p.office}</dt><dd>{title.officeCode}</dd></div>
      <div><dt>{p.status}</dt><dd>{title.acceptsDeposit
        ? <Badge tone="success" icon="checkCircle">{p.acceptsDeposit}</Badge>
        : <Badge tone="neutral" icon="lock">{p.closedTitle}</Badge>}</dd></div>
    </dl>
  )
}

const EMPTY_CLIENT: NewDepositor = { kind: 'NATURAL_PERSON', fullName: '', identityKind: 'NATIONAL_ID', identityNumber: '' }

export function DepositForm({ texts, title, capacity, onDeposited }: {
  texts: Texts; title: TitleLookup; capacity: string; onDeposited: (id: string) => void
}) {
  const p = texts.professionals
  const dialog = useDialog()
  const [kind, setKind] = useState<string>(DEPOSIT_KINDS[0])
  const [client, setClient] = useState<NewDepositor>(EMPTY_CLIENT)
  const [files, setFiles] = useState<DepositFile[]>([])
  const [chooser, setChooser] = useState(0)
  const [failure, setFailure] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  // One key per deposit being prepared: a retry after a lost answer returns the same deposit.
  const [key, setKey] = useState(() => crypto.randomUUID())
  const problems = depositProblems({ depositor: client, files })

  const add = (chosen: FileList | null) => {
    if (!chosen) return
    setFiles((current) => {
      const next = [...current]
      for (const file of Array.from(chosen)) {
        next.push({ file, category: next.some((f) => f.category === 'DEED') ? 'OTHER' : 'DEED' })
      }
      return next
    })
    setChooser((n) => n + 1)
  }
  const set = (field: keyof NewDepositor, value: string) => setClient((c) => ({ ...c, [field]: value }))

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (problems.length > 0) return
    const ok = await dialog.confirm({
      title: fill(p.confirmTitle, { number: title.number }),
      message: fill(p.confirmText, { capacity, n: files.length }),
      confirmLabel: p.submit, cancelLabel: texts.common.cancel,
    })
    if (!ok) return
    setBusy(true)
    setFailure(null)
    try {
      const { id } = await depositsApi.deposit({ titleId: title.id, kind, depositor: client, files }, key)
      setKey(crypto.randomUUID())
      onDeposited(id)
    } catch (error) {
      setFailure(refusal(texts, error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="form" onSubmit={submit} data-professional-deposit-form>
      <p className="small muted">{p.depositIntro}</p>
      {failure && <Alert tone="danger">{failure}</Alert>}
      <Field label={p.depositKind}>
        <select value={kind} onChange={(e) => setKind(e.target.value)}>
          {DEPOSIT_KINDS.map((o) => <option key={o} value={o}>{p.depositKinds[o]}</option>)}
        </select>
      </Field>
      <fieldset className="fieldset">
        <legend>{p.client}</legend>
        <Field label={p.clientKind}>
          <select value={client.kind} onChange={(e) => set('kind', e.target.value)}>
            {DEPOSITOR_KINDS.map((o) => <option key={o} value={o}>{p.clientKinds[o]}</option>)}
          </select>
        </Field>
        <Field label={p.clientName} hint={p.demoHint} required requiredLabel={texts.common.required}>
          <input maxLength={200} value={client.fullName} onChange={(e) => set('fullName', e.target.value)} />
        </Field>
        <Field label={p.identityKind}>
          <select value={client.identityKind} onChange={(e) => set('identityKind', e.target.value)}>
            {IDENTITY_KINDS.map((o) => <option key={o} value={o}>{p.identityKinds[o]}</option>)}
          </select>
        </Field>
        <Field label={p.identityNumber} hint={p.demoHint} required requiredLabel={texts.common.required}>
          <input maxLength={64} value={client.identityNumber} onChange={(e) => set('identityNumber', e.target.value)} />
        </Field>
      </fieldset>
      <fieldset className="fieldset">
        <legend>{p.files}</legend>
        <p className="small muted">{fill(p.filesHint, { max: MAX_FILES })}</p>
        {files.length > 0 && (
          <ul className="plain-list" data-deposit-files>
            {files.map((f, i) => (
              <li key={`${f.file.name}-${i}`} className="form-row" data-file={f.file.name}>
                <span className="grow">{f.file.name}</span>
                <label className="visually-hidden" htmlFor={`category-${i}`}>{fill(p.fileCategory, { name: f.file.name })}</label>
                <select id={`category-${i}`} value={f.category}
                  onChange={(e) => setFiles((all) => all.map((x, j) => (j === i ? { ...x, category: e.target.value } : x)))}>
                  {DOCUMENT_CATEGORIES.map((c) => <option key={c} value={c}>{p.categories[c]}</option>)}
                </select>
                <Button variant="ghost" size="sm" icon="trash" iconOnly onClick={() => setFiles((all) => all.filter((_, j) => j !== i))}>
                  {fill(p.removeFile, { name: f.file.name })}
                </Button>
              </li>
            ))}
          </ul>
        )}
        <Field label={p.chooseFiles}>
          <input key={chooser} type="file" multiple accept=".pdf,.jpg,.jpeg,.png" onChange={(e) => add(e.target.files)} />
        </Field>
      </fieldset>
      {problems.length > 0 && (files.length > 0 || client.fullName || client.identityNumber) && (
        <ul className="small muted" data-deposit-problems>
          {problems.map((code) => <li key={code}>{fill(p.problems[code], { max: MAX_FILES })}</li>)}
        </ul>
      )}
      <div className="form-actions start">
        <Button type="submit" variant="primary" icon="upload" busy={busy} disabled={busy || problems.length > 0}>{p.submit}</Button>
      </div>
    </form>
  )
}
