// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Special grants (security step 4d, ADR-025): one permission for one person and one task,
// limited by an end date and/or a number of uses. Shown with admin.grant.read; requesting
// needs admin.grant.request, deciding admin.grant.approve, revoking admin.grant.revoke.
// The server refuses the requester or the beneficiary as approver, administration
// permissions, and anything that would mix administration with audit.
import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import type { Lang, Texts } from '../../shared/i18n'
import type { Run } from '../../shared/session/types'
import { rolesApi } from '../roles'
import type { CataloguePermission } from '../roles'
import { grantsApi } from './api'
import type { SpecialGrant } from './api'

export function GrantsPanel({ texts, lang, userId, canRequest, canApprove, canRevoke, run }: {
  texts: Texts; lang: Lang; userId: string; canRequest: boolean; canApprove: boolean; canRevoke: boolean; run: Run
}) {
  const [status, setStatus] = useState('PENDING')
  const [list, setList] = useState<SpecialGrant[]>([])
  const [catalogue, setCatalogue] = useState<CataloguePermission[]>([])
  const [notice, setNotice] = useState<string | null>(null)
  const [beneficiary, setBeneficiary] = useState('')
  const [permission, setPermission] = useState('')
  const [office, setOffice] = useState('')
  const [validUntil, setValidUntil] = useState('')
  const [maxUses, setMaxUses] = useState('')
  const [reason, setReason] = useState('')

  const load = async (filter = status) => setList(await grantsApi.list(filter || undefined))
  useEffect(() => {
    void run(async () => {
      if (canRequest) setCatalogue((await rolesApi.catalogue().catch(() => [])).filter((p) => p.delegable))
      await load()
    })
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const submit = (e: FormEvent) => {
    e.preventDefault()
    void run(async () => {
      await grantsApi.request({
        userId: beneficiary.trim(), permission, reason,
        officeCode: office.trim() ? office.trim().toUpperCase() : undefined,
        validUntil: validUntil ? new Date(validUntil).toISOString() : undefined,
        maxUses: maxUses ? Number(maxUses) : undefined,
      })
      setReason('')
      setNotice(texts.requested)
      await load()
    })
  }

  const decide = (id: string, decision: 'approve' | 'reject' | 'revoke') => {
    const why = window.prompt(texts.decisionReason)
    if (why) void run(async () => {
      await grantsApi.decide(id, decision, why)
      setNotice(texts.done)
      await load()
    })
  }

  return (
    <section className="panel wide">
      <h2>{texts.grantsTitle}</h2>
      <p>{texts.grantsIntro}</p>
      {notice && <p className="notice" role="status">{notice}</p>}
      <label className="inline">{texts.status}
        <select value={status} onChange={(e) => { setStatus(e.target.value); void run(() => load(e.target.value)) }}>
          <option value="">{texts.all}</option>
          {['PENDING', 'APPROVED', 'REJECTED', 'REVOKED'].map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
      </label>
      {list.length === 0 && <p>{texts.none}</p>}
      <ul>
        {list.map((g) => (
          <li key={g.id}>
            <code>{g.permission}</code> @ {g.officeCode ?? texts.everywhere} → <code>{g.userId}</code>{' '}
            <small>
              [{g.status}] {g.validUntil ? texts.until.replace('{date}', new Date(g.validUntil).toLocaleString(lang)) : ''}{' '}
              {texts.uses}: {g.uses}{g.maxUses !== null ? `/${g.maxUses}` : ` (${texts.unlimited})`} — {g.reason}
            </small>{' '}
            {g.status === 'PENDING' && canApprove && g.requestedBy !== userId && g.userId !== userId && (
              <>
                <button type="button" onClick={() => decide(g.id, 'approve')}>{texts.approve}</button>{' '}
                <button type="button" className="link" onClick={() => decide(g.id, 'reject')}>{texts.reject}</button>
              </>
            )}
            {g.status === 'PENDING' && g.requestedBy === userId && <em>{texts.requestedByYou}</em>}
            {g.status === 'APPROVED' && canRevoke && g.userId !== userId && (
              <button type="button" className="danger" onClick={() => decide(g.id, 'revoke')}>{texts.revokeGrant}</button>
            )}
          </li>
        ))}
      </ul>

      {canRequest && (
        <details>
          <summary>{texts.newGrant}</summary>
          <form onSubmit={submit} className="grid">
            <label>{texts.beneficiary}<input required pattern="[0-9a-fA-F-]{36}" maxLength={36} value={beneficiary}
              onChange={(e) => setBeneficiary(e.target.value)} /></label>
            <label>{texts.permission}<select required value={permission} onChange={(e) => setPermission(e.target.value)}>
              <option value="" />
              {catalogue.map((p) => (
                <option key={p.code} value={p.code}>{p.code} — {lang === 'fr' ? p.descriptionFr : p.descriptionEn}</option>
              ))}
            </select></label>
            <label>{texts.officeOptional}<input maxLength={16} value={office} onChange={(e) => setOffice(e.target.value)} /></label>
            <label>{texts.validUntil}<input type="datetime-local" value={validUntil}
              onChange={(e) => setValidUntil(e.target.value)} /></label>
            <label>{texts.maxUses}<input type="number" min={1} max={100000} value={maxUses}
              onChange={(e) => setMaxUses(e.target.value)} /></label>
            <label>{texts.requestReason}<input required minLength={10} maxLength={500} value={reason}
              onChange={(e) => setReason(e.target.value)} /></label>
            <button type="submit">{texts.submitRequest}</button>
          </form>
        </details>
      )}
    </section>
  )
}
