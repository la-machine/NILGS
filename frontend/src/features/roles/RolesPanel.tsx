// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Roles and permissions (security step 4d, ADR-025): the catalogue, every role with its
// permissions and scopes, and the four-eyes requests that create, change or retire a
// custom role. Shown with admin.role.read; proposing needs admin.role.define, deciding
// admin.role.approve. The server checks every rule again (requester never approves,
// baseline roles never change, administration and audit never together).
import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import type { Lang, Texts } from '../../shared/i18n'
import type { Run } from '../../shared/session/types'
import { rolesApi } from './api'
import type { CataloguePermission, RoleChangeRequest, RoleDefinition, RoleProposal, Scope } from './api'

type Draft = { kind: 'CREATE' | 'UPDATE'; code: string; nameFr: string; nameEn: string; description: string; permissions: Record<string, Scope> }

const EMPTY: Draft = { kind: 'CREATE', code: '', nameFr: '', nameEn: '', description: '', permissions: {} }

export function RolesPanel({ texts, lang, userId, canDefine, canApprove, run }: {
  texts: Texts; lang: Lang; userId: string; canDefine: boolean; canApprove: boolean; run: Run
}) {
  const [catalogue, setCatalogue] = useState<CataloguePermission[]>([])
  const [list, setList] = useState<RoleDefinition[]>([])
  const [pending, setPending] = useState<RoleChangeRequest[]>([])
  const [draft, setDraft] = useState<Draft | null>(null)
  const [reason, setReason] = useState('')
  const [notice, setNotice] = useState<string | null>(null)

  const load = async () => {
    const [c, r, p] = await Promise.all([rolesApi.catalogue(), rolesApi.list(), rolesApi.requests('PENDING')])
    setCatalogue(c)
    setList(r)
    setPending(p)
  }
  useEffect(() => { void run(load) }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const send = (body: RoleProposal) => run(async () => {
    await rolesApi.propose(body)
    setDraft(null)
    setReason('')
    setNotice(texts.requested)
    await load()
  })

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (!draft) return
    void send({ kind: draft.kind, roleCode: draft.code.trim().toUpperCase(), nameFr: draft.nameFr, nameEn: draft.nameEn,
      description: draft.description || undefined, permissions: draft.permissions, reason })
  }

  const retire = (code: string) => {
    const why = window.prompt(texts.requestReason)
    if (why) void send({ kind: 'RETIRE', roleCode: code, reason: why })
  }

  const decide = (id: string, decision: 'approve' | 'reject') => {
    const why = window.prompt(texts.decisionReason)
    if (why) void run(async () => {
      await rolesApi.decide(id, decision, why)
      setNotice(texts.done)
      await load()
    })
  }

  const describe = (code: string) => {
    const p = catalogue.find((c) => c.code === code)
    return p ? (lang === 'fr' ? p.descriptionFr : p.descriptionEn) : code
  }

  return (
    <section className="panel wide">
      <h2>{texts.rolesTitle}</h2>
      <p>{texts.rolesIntro}</p>
      {notice && <p className="notice" role="status">{notice}</p>}

      {pending.length > 0 && (
        <>
          <h3>{texts.pendingRequests}</h3>
          <ul>
            {pending.map((r) => (
              <li key={r.id}>
                <strong>{r.kind} {r.roleCode}</strong> — {r.reason}
                {r.kind !== 'RETIRE' && <small> ({Object.entries(r.proposal.permissions).map(([c, s]) => `${c}:${s}`).join(', ')})</small>}
                {' '}
                {r.requestedBy === userId
                  ? <em>{texts.requestedByYou}</em>
                  : canApprove && (
                    <>
                      <button type="button" onClick={() => decide(r.id, 'approve')}>{texts.approve}</button>{' '}
                      <button type="button" className="link" onClick={() => decide(r.id, 'reject')}>{texts.reject}</button>
                    </>
                  )}
              </li>
            ))}
          </ul>
        </>
      )}

      {list.map((role) => (
        <details key={role.code}>
          <summary>
            <strong>{role.code}</strong> — {lang === 'fr' ? role.nameFr : role.nameEn}{' '}
            <small>({role.system ? texts.baseline : texts.custom}{role.active ? '' : `, ${texts.retired}`})</small>
          </summary>
          {role.description && <p>{role.description}</p>}
          <ul>
            {Object.entries(role.permissions).sort().map(([code, scope]) => (
              <li key={code}><code>{code}</code> ({scope.toLowerCase()}) — {describe(code)}</li>
            ))}
          </ul>
          {canDefine && !role.system && role.active && (
            <p>
              <button type="button" onClick={() => setDraft({ kind: 'UPDATE', code: role.code, nameFr: role.nameFr,
                nameEn: role.nameEn, description: role.description ?? '', permissions: { ...role.permissions } })}>
                {texts.changeRole}
              </button>{' '}
              <button type="button" className="danger" onClick={() => retire(role.code)}>{texts.retireRole}</button>
            </p>
          )}
        </details>
      ))}

      {canDefine && !draft && <p><button type="button" onClick={() => setDraft(EMPTY)}>{texts.newRole}</button></p>}
      {canDefine && draft && (
        <form onSubmit={submit} className="grid">
          <label>{texts.roleCode}<input required pattern="[A-Z][A-Z0-9_]{2,39}" maxLength={40} value={draft.code}
            disabled={draft.kind === 'UPDATE'} onChange={(e) => setDraft({ ...draft, code: e.target.value.toUpperCase() })} /></label>
          <label>{texts.nameFr}<input required minLength={2} maxLength={100} value={draft.nameFr}
            onChange={(e) => setDraft({ ...draft, nameFr: e.target.value })} /></label>
          <label>{texts.nameEn}<input required minLength={2} maxLength={100} value={draft.nameEn}
            onChange={(e) => setDraft({ ...draft, nameEn: e.target.value })} /></label>
          <label>{texts.description}<input maxLength={500} value={draft.description}
            onChange={(e) => setDraft({ ...draft, description: e.target.value })} /></label>
          <fieldset>
            <legend>{texts.permissionsLabel}</legend>
            {catalogue.map((p) => (
              <label key={p.code} className="inline">
                <code>{p.code}</code> {lang === 'fr' ? p.descriptionFr : p.descriptionEn}
                <select value={draft.permissions[p.code] ?? ''} onChange={(e) => {
                  const permissions = { ...draft.permissions }
                  if (e.target.value) permissions[p.code] = e.target.value as Scope
                  else delete permissions[p.code]
                  setDraft({ ...draft, permissions })
                }}>
                  <option value="">{texts.scopeNone}</option>
                  {p.scopes.map((s) => <option key={s} value={s}>{s.toLowerCase()}</option>)}
                </select>
              </label>
            ))}
          </fieldset>
          <label>{texts.requestReason}<input required minLength={10} maxLength={500} value={reason}
            onChange={(e) => setReason(e.target.value)} /></label>
          <p>
            <button type="submit">{texts.submitRequest}</button>{' '}
            <button type="button" className="link" onClick={() => setDraft(null)}>{texts.back}</button>
          </p>
        </form>
      )}
    </section>
  )
}
