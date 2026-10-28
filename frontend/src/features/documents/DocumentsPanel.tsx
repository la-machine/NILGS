// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The supporting documents of an application (MVP-02, ADR-033), one row per category of its
// type: required or optional, the latest file (name, size, date, download), earlier versions
// folded below. While the application takes documents (a draft), each row has its own upload;
// a new file adds a version and replaces nothing. A refusal is shown on its row, in words.
import { useEffect, useState } from 'react'
import { ApiError } from '../../shared/api/client'
import { fill } from '../../shared/i18n'
import type { Lang, Texts } from '../../shared/i18n'
import { Button } from '../../shared/ui/Button'
import { Card } from '../../shared/ui/Card'
import { Alert, Badge, Skeleton } from '../../shared/ui/Feedback'
import { errorMessage } from '../../shared/ui/errorMessage'
import { documentsApi } from './api'
import type { DocumentCategory, DocumentSource, Documents } from './api'
import { ACCEPT, precheck, readableSize } from './files'

export function DocumentsPanel({ texts, lang, applicationId, editable, source = 'own' }: {
  texts: Texts; lang: Lang; applicationId: string
  /** Whether the person may add files here (their own application, in the categories open now). */
  editable: boolean
  /** The applicant's own application, or a case read by staff. */
  source?: DocumentSource
}) {
  const d = texts.documents
  const [documents, setDocuments] = useState<Documents | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let current = true
    documentsApi.list(applicationId, source)
      .then((list) => { if (current) { setDocuments(list); setFailure(null) } })
      .catch((e) => { if (current) setFailure(errorMessage(texts, e)) })
    return () => { current = false }
  }, [applicationId, attempt]) // eslint-disable-line react-hooks/exhaustive-deps

  const label = (c: DocumentCategory) => (lang === 'fr' ? c.labelFr : c.labelEn)
  const canAdd = editable && !!documents?.acceptsDocuments

  return (
    <Card title={d.title} icon="file"
      description={documents && fill(d.intro, { size: readableSize(documents.maxBytes, lang) })}>
      {failure && (
        <Alert tone="danger" action={<Button size="sm" icon="refresh" onClick={() => setAttempt(attempt + 1)}>{texts.common.retry}</Button>}>
          {failure}
        </Alert>
      )}
      {!failure && !documents && <Skeleton lines={3} />}
      {documents && documents.missingRequired.length > 0 && canAdd && (
        <Alert tone="info">
          {fill(d.missing, {
            list: documents.categories.filter((c) => documents.missingRequired.includes(c.code)).map(label).join(', '),
          })}
        </Alert>
      )}
      {documents && (
        <ul className="document-rows">
          {documents.categories.map((c) => (
            <CategoryRow key={c.code} texts={texts} lang={lang} applicationId={applicationId} category={c} source={source}
              label={label(c)} documents={documents} canAdd={canAdd && documents.openCategories.includes(c.code)}
              onUploaded={() => setAttempt((n) => n + 1)} />
          ))}
        </ul>
      )}
    </Card>
  )
}

function CategoryRow({ texts, lang, applicationId, category, source, label, documents, canAdd, onUploaded }: {
  texts: Texts; lang: Lang; applicationId: string; category: DocumentCategory; source: DocumentSource; label: string
  documents: Documents; canAdd: boolean; onUploaded: () => void
}) {
  const d = texts.documents
  const [file, setFile] = useState<File | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  // One key per chosen file: a retry of the same upload adds one version, not two.
  const [key, setKey] = useState(() => crypto.randomUUID())
  const [latest, ...earlier] = category.versions
  const inputId = `document-${category.code}`

  const choose = (chosen: File | null) => {
    setFile(chosen)
    setKey(crypto.randomUUID())
    const code = chosen && precheck(chosen, documents)
    setProblem(code ? (d.codes[code as keyof Texts['documents']['codes']] ?? code) : null)
  }

  const send = async () => {
    if (!file || problem) return
    setBusy(true)
    try {
      await documentsApi.upload(applicationId, category.code, file, key)
      setFile(null)
      onUploaded()
    } catch (error) {
      setProblem(error instanceof ApiError && error.code in d.codes
        ? d.codes[error.code as keyof Texts['documents']['codes']]
        : errorMessage(texts, error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <li className="document-row" data-category={category.code}>
      <div className="document-row-head">
        <span className="strong">{label}</span>
        <Badge tone={category.required ? (latest ? 'success' : 'warning') : 'neutral'}>
          {category.required ? (latest ? d.provided : d.required) : d.optional}
        </Badge>
      </div>
      {latest
        ? (
          <p className="document-file">
            <a href={documentsApi.contentUrl(applicationId, latest.id, source)} download={latest.originalName}>{latest.originalName}</a>
            <span className="muted"> · {readableSize(latest.sizeBytes, lang)} · {fill(d.version, { n: latest.versionNo })} · <span className="num">{new Date(latest.uploadedAt).toLocaleString(lang)}</span></span>
          </p>
        )
        : <p className="muted">{d.none}</p>}
      {earlier.length > 0 && (
        <details className="disclosure">
          <summary>{fill(d.earlier, { n: earlier.length })}</summary>
          <ul className="document-earlier">
            {earlier.map((v) => (
              <li key={v.id}>
                <a href={documentsApi.contentUrl(applicationId, v.id, source)} download={v.originalName}>{v.originalName}</a>
                <span className="muted"> · {fill(d.version, { n: v.versionNo })} · <span className="num">{new Date(v.uploadedAt).toLocaleString(lang)}</span></span>
              </li>
            ))}
          </ul>
        </details>
      )}
      {canAdd && (
        <div className="document-upload">
          <label htmlFor={inputId} className="visually-hidden">{fill(d.chooseFor, { category: label })}</label>
          <input id={inputId} type="file" accept={ACCEPT} aria-describedby={problem ? `${inputId}-problem` : undefined}
            onChange={(e) => choose(e.target.files?.[0] ?? null)} />
          <Button size="sm" icon="upload" onClick={send} busy={busy} disabled={!file || !!problem || busy}>
            {latest ? d.addVersion : d.add}
          </Button>
          {problem && <p id={`${inputId}-problem`} className="field-error">{problem}</p>}
        </div>
      )}
    </li>
  )
}
