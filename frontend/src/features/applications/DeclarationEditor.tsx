// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The applicant's declaration (ADR-043), for a type that registers a title: in what capacity they apply, the persons of
// the case with their explicit roles, the shares each intended owner holds (from the documents, never split equally by
// default), and whom each representative represents. For a transfer (ADR-042 step 6, D37), the transferors (holders of
// the title who transfer) and the transferees (the new owners, with the shares the deed gives them), never both for one
// person. DEMO data only; nothing here is verified: the office transcribes each person from the documents presented at
// the counter, and a second officer checks the transcription.
import { fill } from '../../shared/i18n'
import type { Texts } from '../../shared/i18n'
import { Button } from '../../shared/ui/Button'
import { Card } from '../../shared/ui/Card'
import { Alert } from '../../shared/ui/Feedback'
import { Field } from '../../shared/ui/Field'
import type { Declaration, DeclaredPerson, Registers } from './api'
import { INTERESTS, ownerRole, representable, rolesFor } from './declaration'

export function DeclarationEditor({ texts, value, onChange, problems, registers = 'CREATION' }: {
  texts: Texts; value: Declaration; onChange: (d: Declaration) => void; problems: string[]; registers?: Registers
}) {
  const k = texts.declaration
  const transfer = registers === 'TRANSFER'
  const roles = rolesFor(registers)
  const set = (patch: Partial<Declaration>) => onChange({ ...value, ...patch })
  const setPerson = (i: number, patch: Partial<DeclaredPerson>) => set({ persons: value.persons.map((p, n) => (n === i ? { ...p, ...patch } : p)) })
  const nextRef = () => {
    let n = value.persons.length + 1
    while (value.persons.some((p) => p.ref === `p${n}`)) n++
    return `p${n}`
  }
  const owners = value.persons.filter((p) => p.roles.includes(ownerRole(registers)))
  const coverable = value.persons.filter((p) => representable(registers).some((r) => p.roles.includes(r)))
  const reps = value.persons.filter((p) => p.roles.includes('REPRESENTATIVE'))
  const nameOf = (ref: string) => value.persons.find((p) => p.ref === ref)?.fullName || ref
  const shareOf = (ref: string) => value.shares.find((s) => s.person === ref)
  const setShare = (ref: string, patch: Partial<{ interest: string; shareNum: string; shareDen: string; usufructTerm: string; usufructUntil: string }>) => {
    const current = shareOf(ref) ?? { person: ref, interest: 'CO_OWNERSHIP', shareNum: '', shareDen: '' }
    set({ shares: [...value.shares.filter((s) => s.person !== ref), { ...current, ...patch }] })
  }
  const mandateOf = (ref: string) => value.mandates.find((m) => m.representative === ref)
  const toggleRepresented = (rep: string, owner: string, on: boolean) => {
    const current = mandateOf(rep) ?? { representative: rep, represents: [], scope: 'APPLY' }
    const represents = on ? [...current.represents, owner] : current.represents.filter((r) => r !== owner)
    set({ mandates: [...value.mandates.filter((m) => m.representative !== rep), { ...current, represents }] })
  }
  return (
    <Card title={k.title} icon="users" description={transfer ? k.transferIntro : k.intro}>
      <Alert tone="warning" title={k.demoTitle}>{k.demoText}</Alert>
      {problems.length > 0 && (
        <Alert tone="info" title={k.problemsTitle}>
          <ul data-declaration-problems>{problems.map((p) => <li key={p}>{problemText(texts, p, nameOf)}</li>)}</ul>
        </Alert>
      )}
      <Field label={k.capacity}>
        <select value={value.capacity} onChange={(e) => set({ capacity: e.target.value as Declaration['capacity'] })}>
          {(['SELF', 'ON_BEHALF_OF_ONE', 'ON_BEHALF_OF_SEVERAL'] as const).map((c) => (
            <option key={c} value={c}>{transfer ? k.transferCapacities[c] : k.capacities[c]}</option>
          ))}
        </select>
      </Field>

      <h3>{k.personsTitle}</h3>
      <ul className="plain-list exception-list">
        {value.persons.map((p, i) => (
          <li key={p.ref} className="exception-item" data-declared-person={p.ref}>
            <fieldset>
              <legend>{fill(k.personLegend, { ref: p.ref })}</legend>
              {roles.map((r) => (
                <label key={r} className="small">
                  <input type="checkbox" checked={p.roles.includes(r)}
                    onChange={(e) => setPerson(i, { roles: e.target.checked ? [...p.roles, r] : p.roles.filter((x) => x !== r) })} />{' '}
                  {k.roles[r]}
                </label>
              ))}
            </fieldset>
            <Field label={k.fullName} hint={k.demoHint}><input value={p.fullName} maxLength={200} onChange={(e) => setPerson(i, { fullName: e.target.value })} /></Field>
            <Field label={k.identityKind}>
              <select value={p.identityKind} onChange={(e) => setPerson(i, { identityKind: e.target.value })}>
                {['NATIONAL_ID', 'PASSPORT', 'RESIDENCE_PERMIT'].map((o) => <option key={o} value={o}>{(k.identityKinds as Record<string, string>)[o] ?? o}</option>)}
              </select>
            </Field>
            <Field label={k.identityNumber} hint={k.demoHint}>
              <input value={p.identityNumber} maxLength={64} onChange={(e) => setPerson(i, { identityNumber: e.target.value })} />
            </Field>
            {p.roles.includes('APPLICANT') && (
              <Field label={k.phone} hint={k.phoneHint}><input value={p.phone ?? ''} maxLength={24} onChange={(e) => setPerson(i, { phone: e.target.value })} /></Field>
            )}
            {value.persons.length > 1 && (
              <div className="form-actions start">
                <Button variant="ghost" icon="trash" onClick={() => set({ persons: value.persons.filter((_, n) => n !== i) })}>{k.removePerson}</Button>
              </div>
            )}
          </li>
        ))}
      </ul>
      {value.persons.length < 20 && (
        <div className="form-actions start">
          <Button variant="secondary" icon="plus" onClick={() => set({ persons: [...value.persons, { ref: nextRef(), kind: 'NATURAL_PERSON', roles: [ownerRole(registers)],
            fullName: '', identityKind: 'NATIONAL_ID', identityNumber: '' }] })}>{k.addPerson}</Button>
        </div>
      )}

      {owners.length > 0 && (
        <>
          <h3>{transfer ? k.transferSharesTitle : k.sharesTitle}</h3>
          <p className="muted small">{transfer ? k.transferSharesIntro : k.sharesIntro}</p>
          {owners.map((o) => {
            const s = shareOf(o.ref)
            return (
              <div key={o.ref} className="form form-inline" data-declared-share={o.ref}>
                <span>{nameOf(o.ref)}</span>
                <select aria-label={fill(k.interestOf, { name: nameOf(o.ref) })} value={s?.interest ?? 'CO_OWNERSHIP'}
                  onChange={(e) => setShare(o.ref, { interest: e.target.value })}>
                  {INTERESTS.map((x) => <option key={x} value={x}>{k.interests[x]}</option>)}
                </select>
                <input aria-label={fill(k.shareNumOf, { name: nameOf(o.ref) })} inputMode="numeric" value={s?.shareNum ?? ''}
                  onChange={(e) => setShare(o.ref, { shareNum: e.target.value })} />
                <span>/</span>
                <input aria-label={fill(k.shareDenOf, { name: nameOf(o.ref) })} inputMode="numeric" value={s?.shareDen ?? ''}
                  onChange={(e) => setShare(o.ref, { shareDen: e.target.value })} />
                {s?.interest === 'USUFRUCT' && (
                  <>
                    <select aria-label={fill(k.termOf, { name: nameOf(o.ref) })} value={s.usufructTerm ?? 'LIFE'}
                      onChange={(e) => setShare(o.ref, { usufructTerm: e.target.value })}>
                      <option value="LIFE">{texts.parties.termLife}</option><option value="UNTIL">{texts.parties.termUntilOption}</option>
                    </select>
                    {s.usufructTerm === 'UNTIL' && (
                      <input type="date" aria-label={fill(k.termDateOf, { name: nameOf(o.ref) })} value={s.usufructUntil ?? ''}
                        onChange={(e) => setShare(o.ref, { usufructUntil: e.target.value })} />
                    )}
                  </>
                )}
              </div>
            )
          })}
        </>
      )}

      {reps.length > 0 && (
        <>
          <h3>{k.mandatesTitle}</h3>
          <p className="muted small">{k.mandatesIntro}</p>
          {reps.map((r) => (
            <fieldset key={r.ref} data-declared-mandate={r.ref}>
              <legend>{fill(k.representsLegend, { name: nameOf(r.ref) })}</legend>
              {coverable.filter((o) => o.ref !== r.ref).map((o) => (
                <label key={o.ref} className="small">
                  <input type="checkbox" checked={mandateOf(r.ref)?.represents.includes(o.ref) ?? false}
                    onChange={(e) => toggleRepresented(r.ref, o.ref, e.target.checked)} />{' '}{nameOf(o.ref)}
                </label>
              ))}
            </fieldset>
          ))}
        </>
      )}
    </Card>
  )
}

function problemText(texts: Texts, code: string, nameOf: (ref: string) => string): string {
  const at = code.indexOf(':')
  const kind = at < 0 ? code : code.slice(0, at)
  const ref = at < 0 ? null : code.slice(at + 1)
  const text = (texts.parties.problems as Record<string, string>)[kind] ?? kind
  return ref ? `${text} (${nameOf(ref)})` : text
}
