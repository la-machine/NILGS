// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The signed-in person's own account: who they are for the system, their roles by office,
// their security keys (add one, remove a lost one, never the last: ADR-028) and the special
// grants in force for them.
import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { fill } from '../../../shared/i18n'
import type { Texts } from '../../../shared/i18n'
import type { PageContext } from '../../../shared/session/types'
import { Button } from '../../../shared/ui/Button'
import { Card, PageHeader } from '../../../shared/ui/Card'
import { useDialog } from '../../../shared/ui/dialogContext'
import { Field } from '../../../shared/ui/Field'
import { Alert, Badge, EmptyState, Skeleton } from '../../../shared/ui/Feedback'
import { Icon } from '../../../shared/ui/Icon'
import { authApi } from '../api'
import type { SecurityKey } from '../api'
import { webAuthnAvailable } from '../webauthn'

export function AccountPage({ ctx, onAddKey }: { ctx: PageContext; onAddKey: (label: string) => Promise<void> }) {
  const { texts, session, notice } = ctx
  const staff = session.accountType === 'STAFF' && session.authLevel === 'FULL'
  const roleName = (code: string) => texts.roleNames[code as keyof Texts['roleNames']] ?? code
  return (
    <div className="page">
      <PageHeader title={texts.nav.account} description={texts.account.intro} breadcrumbLabel={texts.shell.breadcrumb}
        crumbs={[{ label: texts.nav.dashboard, href: '#/dashboard' }, { label: texts.nav.account }]} />
      {notice && <Alert tone="info">{notice}</Alert>}
      <div className="grid-2">
        <Card title={texts.account.profile} icon="user">
          <dl className="properties">
            <div><dt>{texts.displayName}</dt><dd>{session.displayName}</dd></div>
            <div><dt>{texts.account.accountType}</dt><dd>{session.accountType === 'STAFF' ? texts.account.staff : texts.account.applicant}</dd></div>
            <div>
              <dt>{texts.level}</dt>
              <dd>
                {session.authLevel === 'FULL'
                  ? <Badge tone="success" icon="checkCircle">{texts.levelFull}</Badge>
                  : <Badge tone="warning" icon="alert">{texts.levelPassword}</Badge>}
              </dd>
            </div>
            <div><dt>{texts.account.signedInAt}</dt><dd className="num">{new Date(session.authenticatedAt).toLocaleString(ctx.lang)}</dd></div>
            <div><dt>{texts.accountId}</dt><dd><code>{session.userId}</code></dd></div>
          </dl>
        </Card>
        <Card title={texts.roles} icon="shield" description={texts.account.rolesIntro}>
          {session.grants.length === 0
            ? <p className="muted">—</p>
            : (
              <ul className="role-list">
                {session.grants.map((g) => (
                  <li key={`${g.role}-${g.officeCode}`}>
                    <span className="role-name">{roleName(g.role)}</span>
                    <span className="role-office">
                      {g.officeCode ? <><Icon name="office" size={14} />{g.officeCode}</> : texts.account.noOffice}
                    </span>
                  </li>
                ))}
              </ul>
            )}
        </Card>
      </div>
      {staff && <SecurityKeys ctx={ctx} onAddKey={onAddKey} />}
      {session.specialGrants.length > 0 && (
        <Card title={texts.specialGrantsMine} icon="key" description={texts.account.grantsIntro}>
          <ul className="item-list">
            {session.specialGrants.map((g) => (
              <li key={g.id}>
                <code>{g.permission}</code>
                <span className="muted">{g.officeCode ?? texts.everywhere}</span>
                {g.validUntil && <span className="muted">{fill(texts.until, { date: new Date(g.validUntil).toLocaleString(ctx.lang) })}</span>}
                <Badge tone="info">{g.usesLeft === null ? texts.unlimited : fill(texts.usesLeft, { n: g.usesLeft })}</Badge>
              </li>
            ))}
          </ul>
        </Card>
      )}
    </div>
  )
}

function SecurityKeys({ ctx, onAddKey }: { ctx: PageContext; onAddKey: (label: string) => Promise<void> }) {
  const { texts, run, lang } = ctx
  const dialog = useDialog()
  const [keys, setKeys] = useState<SecurityKey[] | null>(null)
  const [required, setRequired] = useState(2)
  const [label, setLabel] = useState('')

  const load = async () => {
    const result = await authApi.keys()
    setKeys(result.keys)
    setRequired(result.required)
  }
  useEffect(() => { void run(load) }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const remove = async (key: SecurityKey) => {
    const ok = await dialog.confirm({
      title: texts.account.removeKeyTitle, message: fill(texts.removeKeyConfirm, { label: key.label }),
      confirmLabel: texts.removeKey, cancelLabel: texts.common.cancel, tone: 'danger',
    })
    if (ok) await run(async () => {
      await authApi.removeKey(key.id)
      await load()
    })
  }

  const add = (e: FormEvent) => {
    e.preventDefault()
    void onAddKey(label.trim() || texts.addKey).then(() => {
      setLabel('')
      return run(load)
    })
  }

  const date = (value: string | null) => (value ? new Date(value).toLocaleString(lang) : texts.never)

  return (
    <Card title={texts.keysTitle} icon="key" description={texts.account.keysIntro} className="keys-list">
      {keys && keys.length < required && <Alert tone="warning">{fill(texts.keysBelowRequired, { required })}</Alert>}
      {keys === null ? <Skeleton lines={2} /> : keys.length === 0 ? <EmptyState icon="key" title={texts.account.noKeys} /> : (
        <ul className="item-list">
          {keys.map((key) => (
            <li key={key.id} data-key={key.label}>
              <span className="item-main">
                <Icon name="key" size={16} />
                <strong>{key.label}</strong>
              </span>
              <small className="muted">{fill(texts.keyDates, { created: date(key.created), used: date(key.lastUsed) })}</small>
              {keys.length > 1 && (
                <Button size="sm" variant="ghost" icon="trash" onClick={() => void remove(key)}>{texts.removeKey}</Button>
              )}
            </li>
          ))}
        </ul>
      )}
      {webAuthnAvailable() && (
        <form onSubmit={add} className="form-inline">
          <Field label={texts.keyLabel}>
            <input maxLength={64} pattern="[\p{L}\p{N} ._'\(\)\-]{1,64}" value={label} onChange={(e) => setLabel(e.target.value)} />
          </Field>
          <Button type="submit" variant="secondary" icon="plus">{texts.addKey}</Button>
        </form>
      )}
    </Card>
  )
}
