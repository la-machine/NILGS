// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, describe, expect, it } from 'vitest'
import { t } from '../../shared/i18n'
import type { Declaration } from './api'
import { cleanedDeclaration, emptyDeclaration } from './declaration'
import { DeclarationEditor } from './DeclarationEditor'
import { PartiesStatus } from './PartiesStatus'

function Harness({ onState }: { onState: (d: Declaration) => void }) {
  const [d, setD] = useState<Declaration>(emptyDeclaration())
  return <DeclarationEditor texts={t('en')} value={d} onChange={(next) => { setD(next); onState(next) }} problems={['MANDATE_MISSING:p2']} />
}

describe('the applicant\'s declaration (ADR-043)', () => {
  afterEach(() => cleanup())

  it('starts with the applicant as the sole intended owner, no share filled in for them', () => {
    const d = emptyDeclaration()
    expect(d.persons[0].roles).toEqual(['APPLICANT', 'INTENDED_OWNER'])
    expect(d.shares).toEqual([])
  })

  it('adds a co-owner with an explicit role and never splits the shares equally by default', () => {
    let state = emptyDeclaration()
    render(<Harness onState={(d) => { state = d }} />)
    fireEvent.click(screen.getByRole('button', { name: 'Add a person' }))
    expect(state.persons).toHaveLength(2)
    expect(state.persons[1].roles).toEqual(['INTENDED_OWNER'])
    expect(state.shares).toEqual([])
    const nums = screen.getAllByLabelText(/numerator/)
    expect(nums).toHaveLength(2)
    expect(nums.every((n) => (n as HTMLInputElement).value === '')).toBe(true)
  })

  it('says what is still missing, naming the person', () => {
    render(<Harness onState={() => {}} />)
    expect(screen.getByText('An intended owner is represented by nobody (p2)')).toBeTruthy()
    expect(screen.getByText('DEMO data, not verified')).toBeTruthy()
  })

  it('sends numbers as numbers and drops what belongs to no owner or representative', () => {
    const d: Declaration = { capacity: 'ON_BEHALF_OF_ONE', persons: [
      { ref: 'p1', kind: 'NATURAL_PERSON', roles: ['APPLICANT', 'REPRESENTATIVE'], fullName: ' DEMO Rep ', identityKind: 'NATIONAL_ID', identityNumber: 'DEMO-1', phone: ' +237 600000001 ' },
      { ref: 'p2', kind: 'NATURAL_PERSON', roles: ['INTENDED_OWNER'], fullName: 'DEMO Owner', identityKind: 'NATIONAL_ID', identityNumber: 'DEMO-2', phone: '' }],
      shares: [{ person: 'p2', interest: 'OWNERSHIP', shareNum: '1', shareDen: '1' }, { person: 'p1', interest: 'OWNERSHIP', shareNum: '1', shareDen: '2' }],
      mandates: [{ representative: 'p1', represents: ['p2', 'p1'], scope: 'APPLY' }] }
    const out = cleanedDeclaration(d) as { persons: { phone?: string; fullName: string }[]; shares: unknown[]; mandates: { represents: string[] }[] }
    expect(out.persons[0]).toMatchObject({ fullName: 'DEMO Rep', phone: '+237 600000001' })
    expect(out.persons[1].phone).toBeUndefined()
    expect(out.shares).toEqual([{ person: 'p2', interest: 'OWNERSHIP', shareNum: 1, shareDen: 1 }])
    expect(out.mandates[0].represents).toEqual(['p2'])
  })

  it('sends a usufruct with its term, and no term with any other right', () => {
    const d: Declaration = { capacity: 'ON_BEHALF_OF_SEVERAL', persons: [
      { ref: 'p1', kind: 'NATURAL_PERSON', roles: ['APPLICANT', 'INTENDED_OWNER'], fullName: 'DEMO A', identityKind: 'NATIONAL_ID', identityNumber: 'DEMO-1', phone: '+237 600000001' },
      { ref: 'p2', kind: 'NATURAL_PERSON', roles: ['INTENDED_OWNER'], fullName: 'DEMO B', identityKind: 'NATIONAL_ID', identityNumber: 'DEMO-2', phone: '' }],
      shares: [{ person: 'p1', interest: 'USUFRUCT', shareNum: '1', shareDen: '1', usufructTerm: 'UNTIL', usufructUntil: '2040-01-01' },
        { person: 'p2', interest: 'BARE_OWNERSHIP', shareNum: '1', shareDen: '1', usufructTerm: 'LIFE' }],
      mandates: [] }
    const out = cleanedDeclaration(d) as { shares: unknown[] }
    expect(out.shares).toEqual([
      { person: 'p1', interest: 'USUFRUCT', shareNum: 1, shareDen: 1, usufructTerm: 'UNTIL', usufructUntil: '2040-01-01' },
      { person: 'p2', interest: 'BARE_OWNERSHIP', shareNum: 1, shareDen: 1 }])
    const life = cleanedDeclaration({ ...d, shares: [{ person: 'p1', interest: 'USUFRUCT', shareNum: '1', shareDen: '1' }] }) as { shares: unknown[] }
    expect(life.shares[0]).toMatchObject({ usufructTerm: 'LIFE' })
  })

  it('says when an identity document had expired', () => {
    render(<PartiesStatus texts={t('en')} parties={[{ ref: 'p2', roles: ['INTENDED_OWNER'], fullName: 'DEMO Owner', status: 'DOCUMENT_EXPIRED', verification: 'UNVERIFIED_DEMO' }]} />)
    expect(screen.getByText('Identity document expired')).toBeTruthy()
  })

  it('shows the applicant where each person stands, always as unverified', () => {
    render(<PartiesStatus texts={t('en')} parties={[{ ref: 'p2', roles: ['INTENDED_OWNER'], fullName: 'DEMO Owner', status: 'MATCHES_DOCUMENT', verification: 'UNVERIFIED_DEMO' }]} />)
    expect(screen.getByText('Corresponds to the document')).toBeTruthy()
    expect(screen.getByText(/never independently verified/)).toBeTruthy()
  })
})

describe('the declaration of a transfer (ADR-042 step 6, D37)', () => {
  afterEach(() => cleanup())

  function TransferHarness({ onState }: { onState: (d: Declaration) => void }) {
    const [d, setD] = useState<Declaration>(emptyDeclaration('TRANSFER'))
    return <DeclarationEditor texts={t('en')} value={d} onChange={(next) => { setD(next); onState(next) }} problems={[]} registers="TRANSFER" />
  }

  it('starts with the applicant as transferee and one transferor, and offers no intended owner', () => {
    const d = emptyDeclaration('TRANSFER')
    expect(d.persons.map((p) => p.roles)).toEqual([['APPLICANT', 'TRANSFEREE'], ['TRANSFEROR']])
    render(<TransferHarness onState={() => {}} />)
    expect(screen.getAllByLabelText('Transferor')).toHaveLength(2)
    expect(screen.getAllByLabelText('Transferee')).toHaveLength(2)
    expect(screen.queryByLabelText('Intended owner')).toBeNull()
    expect(screen.getByText("The transferees' shares")).toBeTruthy()
  })

  it('adds a transferee by default, and sends the transferees\' shares only', () => {
    let state = emptyDeclaration('TRANSFER')
    render(<TransferHarness onState={(d) => { state = d }} />)
    fireEvent.click(screen.getByRole('button', { name: 'Add a person' }))
    expect(state.persons[2].roles).toEqual(['TRANSFEREE'])
    const out = cleanedDeclaration({ ...state, shares: [{ person: 'p1', interest: 'OWNERSHIP', shareNum: '1', shareDen: '1' },
      { person: 'p2', interest: 'OWNERSHIP', shareNum: '1', shareDen: '1' }] }, 'TRANSFER') as { shares: { person: string }[] }
    expect(out.shares.map((s) => s.person)).toEqual(['p1'])
  })

  it('lets a mandate cover a transferor, never an intended owner', () => {
    const d: Declaration = { capacity: 'ON_BEHALF_OF_ONE', persons: [
      { ref: 'p1', kind: 'NATURAL_PERSON', roles: ['APPLICANT', 'REPRESENTATIVE'], fullName: 'DEMO Rep', identityKind: 'NATIONAL_ID', identityNumber: 'DEMO-1', phone: '+237 600000001' },
      { ref: 'p2', kind: 'NATURAL_PERSON', roles: ['TRANSFEROR'], fullName: 'DEMO Seller', identityKind: 'NATIONAL_ID', identityNumber: 'DEMO-2' },
      { ref: 'p3', kind: 'NATURAL_PERSON', roles: ['TRANSFEREE'], fullName: 'DEMO Buyer', identityKind: 'NATIONAL_ID', identityNumber: 'DEMO-3' }],
      shares: [{ person: 'p3', interest: 'OWNERSHIP', shareNum: '1', shareDen: '1' }],
      mandates: [{ representative: 'p1', represents: ['p2'], scope: 'APPLY' }] }
    const out = cleanedDeclaration(d, 'TRANSFER') as { mandates: { represents: string[] }[] }
    expect(out.mandates[0].represents).toEqual(['p2'])
    expect((cleanedDeclaration(d) as { mandates: { represents: string[] }[] }).mandates[0].represents).toEqual([])
  })
})
