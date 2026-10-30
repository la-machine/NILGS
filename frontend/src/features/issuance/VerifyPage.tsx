// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Public verification of a document said to be issued by SNIGF (ADR-039), signed in or not:
// the address the QR code names (#/verify/<number>). A number confirms that a record exists
// and its status, never the paper in hand; only the exact PDF, uploaded, can be confirmed
// identical. The answer names the type, date, issuing office and status, and nothing about
// the holder, the case or the parcel. An unverified document is never called a forgery.
import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { fill } from '../../shared/i18n'
import type { Lang, Texts } from '../../shared/i18n'
import { href, navigate } from '../../shared/router/router'
import { Button } from '../../shared/ui/Button'
import { Card } from '../../shared/ui/Card'
import { Field } from '../../shared/ui/Field'
import { Alert, Badge } from '../../shared/ui/Feedback'
import { errorMessage } from '../../shared/ui/errorMessage'
import { issuanceApi, normaliseNumber, NUMBER_PATTERN } from './api'
import type { Verification } from './api'
import { OUTCOME_TONE, STATUS_TONE } from './tones'

/** The largest file the server takes for verification (SNIGF_ISSUANCE_MAX_UPLOAD_BYTES). */
const MAX_BYTES = 2 * 1024 * 1024

export function VerifyPage({ texts, lang, number: fromAddress }: { texts: Texts; lang: Lang; number?: string }) {
  const k = texts.issuance
  // Remounted for each number in the address (keyed by it), so the address seeds the form.
  const [number, setNumber] = useState(() => normaliseNumber(fromAddress ?? ''))
  const [touched, setTouched] = useState(() => !!fromAddress && !NUMBER_PATTERN.test(normaliseNumber(fromAddress)))
  const [file, setFile] = useState<File | null>(null)
  const [result, setResult] = useState<Verification | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [busy, setBusy] = useState(() => NUMBER_PATTERN.test(normaliseNumber(fromAddress ?? '')))

  const run = async (action: () => Promise<Verification>) => {
    setBusy(true)
    setFailure(null)
    setResult(null)
    try {
      setResult(await action())
    } catch (e) {
      setFailure(errorMessage(texts, e))
    } finally {
      setBusy(false)
    }
  }

  // Opened from a QR code: look the number up at once.
  useEffect(() => {
    const n = normaliseNumber(fromAddress ?? '')
    if (!NUMBER_PATTERN.test(n)) return undefined
    let current = true
    issuanceApi.verifyNumber(n)
      .then((found) => { if (current) setResult(found) })
      .catch((e) => { if (current) setFailure(errorMessage(texts, e)) })
      .finally(() => { if (current) setBusy(false) })
    return () => { current = false }
  }, [fromAddress]) // eslint-disable-line react-hooks/exhaustive-deps

  const normalised = normaliseNumber(number)
  const numberError = touched && !NUMBER_PATTERN.test(normalised) ? k.numberInvalid : null
  const fileError = file && file.size > MAX_BYTES ? k.fileTooLarge : null

  const lookUp = (e: FormEvent) => {
    e.preventDefault()
    setTouched(true)
    if (!NUMBER_PATTERN.test(normalised)) return
    if (fromAddress !== normalised) navigate(href(['verify', normalised]), { replace: true })
    else void run(() => issuanceApi.verifyNumber(normalised))
  }
  const check = (e: FormEvent) => {
    e.preventDefault()
    if (!file || fileError) return
    void run(() => issuanceApi.verifyFile(file))
  }

  return (
    <div className="page page-narrow verify-page">
      <h1>{k.verifyTitle}</h1>
      <p className="lead">{k.verifyIntro}</p>
      <Alert tone="warning" title={k.demoTitle}>{k.verifyDemo}</Alert>
      <div className="verify-forms">
        <Card title={k.byFileTitle} icon="upload" description={k.byFileIntro}>
          <form className="form" onSubmit={check}>
            <Field label={k.file} hint={k.fileHint} error={fileError}>
              <input type="file" accept="application/pdf,.pdf" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
            </Field>
            <div className="form-actions start">
              <Button type="submit" variant="primary" icon="checkCircle" busy={busy} disabled={busy || !file || !!fileError}>
                {k.checkFile}
              </Button>
            </div>
          </form>
        </Card>
        <Card title={k.byNumberTitle} icon="search" description={k.byNumberIntro}>
          <form className="form" onSubmit={lookUp}>
            <Field label={k.number} hint={k.numberHint} error={numberError}>
              <input className="mono" value={number} maxLength={40} autoComplete="off" spellCheck={false}
                onChange={(e) => setNumber(e.target.value)} onBlur={() => setTouched(true)} />
            </Field>
            <div className="form-actions start">
              <Button type="submit" icon="search" busy={busy} disabled={busy}>{k.lookUp}</Button>
            </div>
          </form>
        </Card>
      </div>
      {failure && <Alert tone="danger">{failure}</Alert>}
      {result && <Answer texts={texts} lang={lang} result={result} />}
      <p className="small muted">{k.printedCopies}</p>
    </div>
  )
}

function Answer({ texts, lang, result }: { texts: Texts; lang: Lang; result: Verification }) {
  const k = texts.issuance
  const d = result.document
  const explanation = (k.reasons as Record<string, string>)[result.reason] ?? k.outcomeText[result.outcome]
  return (
    <section className="verify-answer" aria-live="polite" data-outcome={result.outcome} data-reason={result.reason}>
      <Alert tone={OUTCOME_TONE[result.outcome]} title={k.outcomes[result.outcome]}>
        {explanation}
        {result.outcome === 'SUPERSEDED' && d?.replacement && <> {fill(k.replacementText, { number: d.replacement })}</>}
      </Alert>
      <p className="small">{result.scope === 'EXACT_FILE' ? k.scopeFile : k.scopeRecord}</p>
      {d && (
        <dl className="properties">
          <div><dt>{k.number}</dt><dd className="mono">{d.number}</dd></div>
          <div><dt>{k.type}</dt><dd>{(k.types as Record<string, string>)[d.type] ?? d.type}</dd></div>
          <div><dt>{k.status}</dt><dd><Badge tone={STATUS_TONE[d.status]}>{k.statuses[d.status]}</Badge></dd></div>
          <div><dt>{k.issuedAt}</dt><dd className="num">{new Date(d.issuedAt).toLocaleString(lang)}</dd></div>
          {d.issuingOffice && (
            <div><dt>{k.office}</dt><dd>{lang === 'fr' ? d.issuingOffice.nameFr : d.issuingOffice.nameEn}
              {' '}<span className="mono small">({d.issuingOffice.code})</span></dd></div>
          )}
          {d.replacement && (
            <div><dt>{k.replacedBy}</dt><dd><a className="mono" href={href(['verify', d.replacement])}>{d.replacement}</a></dd></div>
          )}
        </dl>
      )}
    </section>
  )
}
