// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The professional's own page (#/professional): their credential and its status, the history of
// their account, then, for an active notary, the restricted lookup with the deposit, and their own
// deposits. Each block appears only for a session holding the permission behind it; a requested,
// verified, suspended or revoked account sees its status and nothing else.
import { useEffect, useState } from 'react'
import type { Lang, Texts } from '../../shared/i18n'
import { href, navigate } from '../../shared/router/router'
import { canOwn, canSomewhere } from '../../shared/session/access'
import type { PageContext } from '../../shared/session/types'
import { Card, PageHeader } from '../../shared/ui/Card'
import { Alert, Badge, EmptyState, Skeleton } from '../../shared/ui/Feedback'
import { errorMessage } from '../../shared/ui/errorMessage'
import { depositsApi, professionalApi } from './api'
import type { Deposit, Own, TitleLookup } from './api'
import { DepositForm, LookupResult, TitleLookupForm } from './DepositForm'
import { History } from './History'
import { CONFLICT_TONE, DEPOSIT_TONE, STATUS_TONE } from './tones'

export function OwnPage({ ctx }: { ctx: PageContext }) {
  const { texts, session, lang } = ctx
  const p = texts.professionals
  const [own, setOwn] = useState<Own | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [found, setFound] = useState<TitleLookup | null>(null)
  const submits = canSomewhere(session, 'register.deposit.submit')
  const reads = canOwn(session, 'register.deposit.read')

  // Read again when the session's permissions change (an approval, a suspension, an office change),
  // so the status never contradicts what the page offers.
  const permissions = session.permissions.join(' ')
  useEffect(() => {
    let current = true
    professionalApi.me()
      .then((loaded) => { if (current) setOwn(loaded) })
      .catch((e) => { if (current) setFailure(errorMessage(texts, e)) })
    return () => { current = false }
  }, [permissions]) // eslint-disable-line react-hooks/exhaustive-deps

  if (failure && !own) return <div className="page"><Alert tone="danger">{failure}</Alert></div>
  if (!own) return <div className="page" aria-busy="true"><Skeleton lines={6} /></div>
  const { profile } = own
  const capacity = p.professions[profile.profession].toLowerCase()

  return (
    <div className="page" data-professional-page={profile.status}>
      <PageHeader title={p.ownTitle} description={p.ownIntro} breadcrumbLabel={texts.shell.breadcrumb}
        crumbs={[{ label: texts.nav.dashboard, href: href(['dashboard']) }, { label: p.ownTitle }]} />
      <Card title={p.statusTitle} icon="userCheck" description={p.statusText[profile.status]}>
        <dl className="properties">
          <div><dt>{p.status}</dt><dd data-status={profile.status}><Badge tone={STATUS_TONE[profile.status]}>{p.statuses[profile.status]}</Badge></dd></div>
          <div><dt>{p.profession}</dt><dd>{p.professions[profile.profession]}</dd></div>
          <div><dt>{p.chamber}</dt><dd>{profile.chamber}</dd></div>
          <div><dt>{p.registrationNumber}</dt><dd className="mono">{profile.registrationNumber}</dd></div>
          <div><dt>{p.jurisdiction}</dt><dd>{profile.jurisdiction}</dd></div>
          <div><dt>{p.offices}</dt><dd>{profile.offices.length > 0 ? profile.offices.join(', ') : p.none}</dd></div>
        </dl>
        {profile.profession === 'SWORN_SURVEYOR' && profile.status === 'ACTIVE' && <Alert tone="info">{p.surveyorNote}</Alert>}
      </Card>

      {submits && (
        <Card title={p.lookupTitle} icon="search" description={p.lookupIntro}>
          <TitleLookupForm texts={texts} onFound={setFound} />
          {found && <LookupResult texts={texts} title={found} />}
          {found?.acceptsDeposit && (
            <DepositForm key={found.id} texts={texts} title={found} capacity={capacity}
              onDeposited={(id) => navigate(href(['professional', 'deposits', id]))} />
          )}
        </Card>
      )}

      {reads && <DepositList texts={texts} lang={lang} />}

      <Card title={p.historyTitle} icon="clock">
        <History texts={texts} lang={lang} entries={own.history} />
      </Card>
    </div>
  )
}

function DepositList({ texts, lang }: { texts: Texts; lang: Lang }) {
  const p = texts.professionals
  const [rows, setRows] = useState<Deposit[] | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  useEffect(() => {
    let current = true
    depositsApi.list()
      .then((loaded) => { if (current) setRows(loaded) })
      .catch((e) => { if (current) setFailure(errorMessage(texts, e)) })
    return () => { current = false }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <Card title={p.depositsTitle} icon="lock" description={p.depositsIntro} padded={rows === null || rows.length === 0}>
      {failure && <Alert tone="danger">{failure}</Alert>}
      {rows === null && !failure && <Skeleton lines={3} />}
      {rows !== null && rows.length === 0 && <EmptyState icon="file" title={p.noDeposits} />}
      {rows !== null && rows.length > 0 && (
        <table className="table table-responsive" data-deposits>
          <thead>
            <tr>
              <th scope="col">{p.title}</th><th scope="col">{p.kind}</th><th scope="col">{p.depositor}</th>
              <th scope="col">{p.status}</th><th scope="col">{p.depositedAt}</th><th scope="col" className="num-col">{p.documents}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((d) => (
              <tr key={d.id} data-deposit={d.id} data-status={d.status}>
                <td data-label={p.title}><a className="mono strong" href={href(['professional', 'deposits', d.id])}>{d.titleNumber}</a></td>
                <td data-label={p.kind}>{(p.depositKinds as Record<string, string>)[d.kind] ?? d.kind}</td>
                <td data-label={p.depositor}>{d.depositorName}</td>
                <td data-label={p.status} data-conflict={d.conflict}>
                  <Badge tone={DEPOSIT_TONE[d.status]}>{p.depositStatuses[d.status]}</Badge>
                  {d.conflict === 'UNDER_REVIEW' && <> <Badge tone={CONFLICT_TONE[d.conflict]}>{p.conflictStates[d.conflict]}</Badge></>}
                </td>
                <td data-label={p.depositedAt} className="num">{new Date(d.depositedAt).toLocaleString(lang)}</td>
                <td data-label={p.documents} className="num-col num">{d.documents}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Card>
  )
}
