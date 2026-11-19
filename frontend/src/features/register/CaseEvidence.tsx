// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// What the two conservators examine before signing an inscription from a case (ADR-043, owner's review): the approved owners,
// their rights and shares, each identity as transcribed and checked, its document's validity and any exception, the
// mandates and the documents cited, which open as attachments. Read-only; the server audits every read and never sends
// a phone number or an address. For a transfer (ADR-042 step 6), the owners are the transferees, and the transferors, the
// title and the deposit are shown too. Every identity is DEMO, never independently verified.
import { useState } from 'react'
import { fill } from '../../shared/i18n'
import type { Lang, Texts } from '../../shared/i18n'
import { Button } from '../../shared/ui/Button'
import { Alert, Badge } from '../../shared/ui/Feedback'
import { errorMessage } from '../../shared/ui/errorMessage'
import { registerApi } from './api'
import type { CaseEvidence as Evidence } from './api'

export function CaseEvidence({ texts, lang, applicationId }: { texts: Texts; lang: Lang; applicationId: string }) {
  const k = texts.register
  const p = texts.parties
  const [evidence, setEvidence] = useState<Evidence | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const open = async () => {
    setFailure(null)
    try {
      setEvidence(await registerApi.caseEvidence(applicationId))
    } catch (e) {
      setFailure(errorMessage(texts, e))
    }
  }
  const fileName = (id: string | undefined) => evidence?.files.find((f) => f.id === id)?.name
  if (!evidence) {
    return (
      <div className="form-actions start">
        <Button variant="secondary" icon="eye" onClick={() => void open()}>{k.examineEvidence}</Button>
        {failure && <Alert tone="danger">{failure}</Alert>}
      </div>
    )
  }
  return (
    <div data-case-evidence>
      <Alert tone="warning">{k.evidenceIntro}</Alert>
      <p className="small muted">
        {fill(k.evidenceStatement, { version: evidence.statement.version, who: evidence.statement.enteredBy,
          check: evidence.statement.check ? fill(k.evidenceChecked, { who: evidence.statement.check.by }) : p.notChecked })}
      </p>
      <ul className="plain-list exception-list">
        {evidence.owners.map((o) => (
          <li key={`${o.ref}-${o.interest}`} className="exception-item" data-evidence-owner={o.ref}>
            <div className="exception-head">
              <strong>{o.fullName}</strong>
              <Badge tone="neutral">{(p.interests as Record<string, string>)[o.interest] ?? o.interest} {o.shareNum}/{o.shareDen}</Badge>
              {o.usufructTerm && (
                <Badge tone="neutral">{o.usufructTerm === 'LIFE' ? p.termLife : fill(p.termUntil, { date: o.usufructUntil ?? '' })}</Badge>
              )}
            </div>
            <dl className="properties small">
              <div><dt>{p.identity}</dt><dd className="mono">{o.identityKind} {o.identityNumber}</dd></div>
              <div><dt>{p.verification}</dt><dd>{p.unverifiedShort}</dd></div>
              <div><dt>{p.entered}</dt><dd>{fill(p.versionLine, { n: o.identityVersion, who: o.transcribedBy })}</dd></div>
              <div>
                <dt>{p.checkTitle}</dt>
                <dd>{o.check ? `${o.check.outcome === 'MATCHES' ? p.matches : p.doesNotMatch} (${o.check.by})` : p.notChecked}</dd>
              </div>
              {o.validUntil && (
                <div>
                  <dt>{p.validUntil}</dt>
                  <dd>{new Date(o.validUntil).toLocaleDateString(lang)}{o.expiredAtTranscription ? ` · ${p.expiredAtTranscription}` : ''}</dd>
                </div>
              )}
              {o.exception && <div><dt>{p.exceptionTitle}</dt><dd>{fill(p.exceptionLine, { who: o.exception.by, reason: o.exception.reason })}</dd></div>}
              {o.sourceFile && (
                <div><dt>{p.sourceFile}</dt><dd><a href={registerApi.caseEvidenceFileUrl(applicationId, o.sourceFile)}>{fileName(o.sourceFile) ?? o.sourceFile}</a></dd></div>
              )}
              {o.basisFile && (
                <div><dt>{p.basisFile}</dt><dd><a href={registerApi.caseEvidenceFileUrl(applicationId, o.basisFile)}>{fileName(o.basisFile) ?? o.basisFile}</a></dd></div>
              )}
            </dl>
          </li>
        ))}
      </ul>
      {evidence.registers === 'TRANSFER' && (
        <div data-evidence-transfer>
          {evidence.title && <p className="small">{fill(k.evidenceTitle, { number: evidence.title.number, version: evidence.title.version })}</p>}
          {evidence.deposit && (
            <p className="small">{fill(k.evidenceDeposit, { status: evidence.deposit.status, date: new Date(evidence.deposit.depositedAt).toLocaleDateString(lang) })}</p>
          )}
          <h4>{k.evidenceTransferors}</h4>
          <ul className="plain-list small">
            {(evidence.transferors ?? []).map((t) => (
              <li key={t.ref} data-evidence-transferor={t.ref}>
                <strong>{t.fullName}</strong> · <span className="mono">{t.identityKind} {t.identityNumber}</span> · {p.unverifiedShort}
                {' · '}{t.check ? `${t.check.outcome === 'MATCHES' ? p.matches : p.doesNotMatch} (${t.check.by})` : p.notChecked}
              </li>
            ))}
          </ul>
        </div>
      )}
      {evidence.mandates.length > 0 && (
        <ul className="plain-list small">
          {evidence.mandates.map((m) => (
            <li key={`${m.representative}-${m.version}`} data-evidence-mandate>
              {fill(k.evidenceMandate, { representative: m.representative, represents: m.represents.join(', ') })}{' '}
              {m.documentFile && <a href={registerApi.caseEvidenceFileUrl(applicationId, m.documentFile)}>{fileName(m.documentFile) ?? p.mandateFile}</a>}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
