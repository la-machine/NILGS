// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The applicant's declaration (ADR-043) as data: where a new draft starts, and what is sent to the server. A CREATION
// declares intended owners; a TRANSFER (ADR-042 step 6, D37) declares the transferors, holders of the title who transfer,
// and the transferees, the proposed new owners with the shares the deed gives them, never both for one person.
import type { Declaration, DeclaredRole, Registers } from './api'

/** The roles a person of the case may have, by what the procedure registers. */
export function rolesFor(registers: Registers): DeclaredRole[] {
  return registers === 'TRANSFER' ? ['APPLICANT', 'REPRESENTATIVE', 'TRANSFEROR', 'TRANSFEREE'] : ['APPLICANT', 'REPRESENTATIVE', 'INTENDED_OWNER']
}
/** Whose shares the declaration gives: the intended owners of a CREATION, the transferees of a TRANSFER. */
export function ownerRole(registers: Registers): DeclaredRole {
  return registers === 'TRANSFER' ? 'TRANSFEREE' : 'INTENDED_OWNER'
}
/** Whom a mandate may cover: the intended owners; for a TRANSFER, the transferors and the transferees. */
export function representable(registers: Registers): DeclaredRole[] {
  return registers === 'TRANSFER' ? ['TRANSFEROR', 'TRANSFEREE'] : ['INTENDED_OWNER']
}
/** @deprecated The roles of a CREATION; use {@link rolesFor}. */
export const ROLES: DeclaredRole[] = rolesFor('CREATION')
export const INTERESTS = ['OWNERSHIP', 'CO_OWNERSHIP', 'USUFRUCT', 'BARE_OWNERSHIP'] as const

export function emptyDeclaration(registers: Registers = 'CREATION'): Declaration {
  if (registers === 'TRANSFER') {
    return {
      capacity: 'SELF',
      persons: [
        { ref: 'p1', kind: 'NATURAL_PERSON', roles: ['APPLICANT', 'TRANSFEREE'], fullName: '', identityKind: 'NATIONAL_ID', identityNumber: '', phone: '' },
        { ref: 'p2', kind: 'NATURAL_PERSON', roles: ['TRANSFEROR'], fullName: '', identityKind: 'NATIONAL_ID', identityNumber: '' },
      ],
      shares: [],
      mandates: [],
    }
  }
  return {
    capacity: 'SELF',
    persons: [{ ref: 'p1', kind: 'NATURAL_PERSON', roles: ['APPLICANT', 'INTENDED_OWNER'], fullName: '', identityKind: 'NATIONAL_ID', identityNumber: '', phone: '' }],
    shares: [],
    mandates: [],
  }
}

/** The declaration as the server takes it: empty optional values dropped, numbers as numbers. */
export function cleanedDeclaration(d: Declaration, registers: Registers = 'CREATION'): Record<string, unknown> {
  const owners = new Set(d.persons.filter((p) => p.roles.includes(ownerRole(registers))).map((p) => p.ref))
  const coverable = new Set(d.persons.filter((p) => representable(registers).some((r) => p.roles.includes(r))).map((p) => p.ref))
  const reps = new Set(d.persons.filter((p) => p.roles.includes('REPRESENTATIVE')).map((p) => p.ref))
  return {
    capacity: d.capacity,
    persons: d.persons.map((p) => {
      const out: Record<string, unknown> = { ref: p.ref, kind: p.kind, roles: p.roles, fullName: p.fullName.trim(), identityKind: p.identityKind,
        identityNumber: p.identityNumber.trim() }
      if (p.phone?.trim()) out.phone = p.phone.trim()
      return out
    }),
    shares: d.shares.filter((s) => owners.has(s.person)).map((s) => {
      const { usufructTerm, usufructUntil, ...rest } = s
      const share: Record<string, unknown> = { ...rest, shareNum: Number(s.shareNum), shareDen: Number(s.shareDen) }
      // The usufruct's term goes with a usufruct only: LIFE, or UNTIL a date.
      if (s.interest === 'USUFRUCT') {
        share.usufructTerm = usufructTerm || 'LIFE'
        if (share.usufructTerm === 'UNTIL' && usufructUntil) share.usufructUntil = usufructUntil
      }
      return share
    }),
    mandates: d.mandates.filter((m) => reps.has(m.representative)).map((m) => ({ ...m, represents: m.represents.filter((r) => coverable.has(r)) })),
  }
}
