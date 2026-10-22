// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The audit screen (S1-AC5): search events, verify the whole chain, sign a checkpoint,
// list and download checkpoint files to keep off-host, read the public keys. Each action
// appears only with its own permission (ADR-025), held through a role or a special grant.
// A grant-only action is never run on its own: the button says it uses the grant, asks for
// confirmation, and the session is reloaded afterwards to show the uses left (ADR-026).
// Every search and download is itself recorded in the audit trail.
import { useState } from 'react'
import type { FormEvent } from 'react'
import { fill } from '../../shared/i18n'
import type { Texts } from '../../shared/i18n'
import type { Access } from '../../shared/session/access'
import type { Run } from '../../shared/session/types'
import { auditApi } from './api'
import type { AuditEvent, CheckpointKey, CheckpointSummary, Verification } from './api'

export function AuditPanel({ texts, access, onGrantUsed, run }: {
  texts: Texts; access: (permission: string) => Access; onGrantUsed: () => Promise<void>; run: Run
}) {
  const [action, setAction] = useState('')
  const [actorId, setActorId] = useState('')
  const [page, setPage] = useState(0)
  const [events, setEvents] = useState<AuditEvent[] | null>(null)
  const [more, setMore] = useState(false)
  const [verification, setVerification] = useState<Verification | null>(null)
  const [checkpoints, setCheckpoints] = useState<CheckpointSummary[] | null>(null)
  const [keys, setKeys] = useState<CheckpointKey[] | null>(null)

  /** Runs a protected action; through a special grant only after confirmation. */
  const perform = (permission: string, act: () => Promise<void>) => {
    const a = access(permission)
    if (!a) return
    if (a.kind === 'grant' && !window.confirm(fill(texts.useGrantConfirm,
      { permission, n: a.grant.usesLeft ?? texts.unlimited }))) return
    void run(async () => {
      await act()
      if (a.kind === 'grant') await onGrantUsed()
    })
  }

  const badge = (permission: string) => {
    const a = access(permission)
    return a?.kind === 'grant'
      ? <small className="grant"> ({texts.viaGrant}, {a.grant.usesLeft === null ? texts.unlimited : fill(texts.usesLeft, { n: a.grant.usesLeft })})</small>
      : null
  }

  const search = (next: number) => perform('audit.event.search', async () => {
    const result = await auditApi.events({ action: action.trim().toUpperCase() || undefined, actorId: actorId.trim() || undefined, page: next })
    setEvents(result.events)
    setMore(result.more)
    setPage(next)
  })

  return (
    <section className="panel wide">
      <h2>{texts.auditTitle}</h2>
      {access('audit.event.search') && (
        <form className="inline" onSubmit={(e: FormEvent) => { e.preventDefault(); search(0) }}>
          <label>{texts.action}<input value={action} maxLength={64} onChange={(e) => setAction(e.target.value)} /></label>
          <label>{texts.actor}<input value={actorId} maxLength={36} onChange={(e) => setActorId(e.target.value)} /></label>
          <button type="submit">{texts.find}</button>{badge('audit.event.search')}
        </form>
      )}
      {events?.length === 0 && <p>{texts.none}</p>}
      {events && events.length > 0 && (
        <table className="events">
          <thead><tr><th>#</th><th>UTC</th><th>{texts.action}</th><th>Outcome</th><th>{texts.actor}</th><th>Details</th></tr></thead>
          <tbody>
            {events.map((e) => (
              <tr key={e.id}>
                <td>{e.chainPos}</td>
                <td>{e.occurredAt.replace('T', ' ').slice(0, 19)}</td>
                <td>{e.action}</td>
                <td>{e.outcome}</td>
                <td>{e.actorType === 'USER' ? e.actorId : e.actorLabel ?? e.actorType}</td>
                <td><code>{JSON.stringify(e.details)}</code></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {more && <button type="button" onClick={() => search(page + 1)}>{texts.more}</button>}

      <p className="actions">
        {access('audit.chain.verify') && (
          <button type="button" onClick={() => perform('audit.chain.verify', async () => setVerification(await auditApi.verify()))}>
            {texts.verify}{badge('audit.chain.verify')}
          </button>
        )}
        {access('audit.checkpoint.create') && (
          <button type="button" onClick={() => perform('audit.checkpoint.create', async () => {
            await auditApi.checkpoint()
            if (access('audit.checkpoint.list')?.kind === 'role') setCheckpoints(await auditApi.checkpoints())
          })}>{texts.checkpoint}{badge('audit.checkpoint.create')}</button>
        )}
        {access('audit.key.read') && (
          <button type="button" onClick={() => perform('audit.key.read', async () => setKeys(await auditApi.keys()))}>
            {texts.publicKeys}{badge('audit.key.read')}
          </button>
        )}
      </p>
      {verification && (verification.verified
        ? <p className="notice" role="status">{fill(texts.verified, { from: verification.checkedFrom, to: verification.checkedTo, rows: verification.rowsChecked })}</p>
        : <p className="error" role="alert">{fill(texts.broken, { pos: verification.firstMismatchPos ?? '?', kind: verification.mismatchKind ?? '?' })}</p>)}
      {keys && (
        <ul className="keys">
          {keys.map((k) => <li key={k.version}>v{k.version} <pre>{k.pem}</pre></li>)}
        </ul>
      )}

      {access('audit.checkpoint.list') && (
        <>
          <h3>{texts.checkpoints}</h3>
          {checkpoints === null
            ? <button type="button" className="link" onClick={() => perform('audit.checkpoint.list', async () => setCheckpoints(await auditApi.checkpoints()))}>
                {texts.showCheckpoints}{badge('audit.checkpoint.list')}
              </button>
            : (
              <ul>
                {checkpoints.map((c) => (
                  <li key={c.id}>#{c.chainPos} — {c.createdAt} — v{c.keyVersion}
                    {access('audit.checkpoint.download') && (
                      <> — <a href={c.file} download onClick={(e) => {
                        const a = access('audit.checkpoint.download')
                        if (a?.kind === 'grant') {
                          if (!window.confirm(fill(texts.useGrantConfirm, { permission: 'audit.checkpoint.download', n: a.grant.usesLeft ?? texts.unlimited }))) e.preventDefault()
                          else window.setTimeout(() => void onGrantUsed(), 1000)
                        }
                      }}>{texts.download}</a>{badge('audit.checkpoint.download')}</>
                    )}
                  </li>
                ))}
              </ul>
            )}
        </>
      )}
    </section>
  )
}
