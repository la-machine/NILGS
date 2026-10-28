// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { t } from '../../shared/i18n'
import type { PageContext, Session } from '../../shared/session/types'
import { DialogProvider } from '../../shared/ui/Dialog'
import { paymentsApi } from './api'
import type { FinanceException } from './api'
import { FinancePage } from './FinancePage'

const ctx = (): PageContext => {
  const session: Session = {
    userId: 'f1', displayName: 'Finance', accountType: 'STAFF', roles: ['FINANCE_OFFICER'], grants: [{ role: 'FINANCE_OFFICER', officeCode: null }],
    permissions: ['payment.reconcile'], specialGrants: [], authLevel: 'FULL', nextStep: 'DONE', identityVerified: true, authenticatedAt: '2026-10-27T08:00:00Z',
  }
  return { texts: t('en'), lang: 'en', session, run: async (a) => { await a() }, refreshSession: async () => {} }
}

const mismatch: FinanceException = {
  id: 'r1', transactionId: 'SIMTX-0001', reportedReference: 'DEMO-PAY-2026-00000002-X', reportedAmount: 24000, reportedCurrency: 'XAF',
  outcome: 'AMOUNT_MISMATCH', receivedAt: '2026-10-27T10:00:00Z', requestReference: 'DEMO-PAY-2026-00000002-X', expectedAmount: 25000,
  requestStatus: 'PENDING', applicationReference: 'DEMO-APP-2026-000002-X', resolution: null, resolutionNote: null, resolvedByName: null, resolvedAt: null,
}

describe('the finance officer\'s reconciliation', () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks() })

  it('lists the open exceptions and resolves one with a note, after confirmation, never as a payment', async () => {
    vi.spyOn(paymentsApi, 'summary').mockResolvedValue({ demo: true, pending: 1, paid: 3, failed: 0, expired: 1, openExceptions: 1 })
    vi.spyOn(paymentsApi, 'exceptions').mockResolvedValue([mismatch])
    const resolve = vi.spyOn(paymentsApi, 'resolve').mockResolvedValue({ id: 'x1' })
    render(<DialogProvider><FinancePage ctx={ctx()} params={new URLSearchParams()} /></DialogProvider>)
    expect(await screen.findByText('Amount mismatch')).toBeTruthy()
    expect(screen.getByText(/no money moved/)).toBeTruthy()
    expect(screen.getByText('The provider reported another amount than the one requested. The request stays unpaid.')).toBeTruthy()
    expect(paymentsApi.exceptions).toHaveBeenCalledWith(false, 0)

    fireEvent.click(screen.getByRole('button', { name: 'Resolve' }))
    expect(screen.getByText('From 10 to 1000 characters.')).toBeTruthy()
    fireEvent.change(screen.getByLabelText(/^Note/), { target: { value: 'Simulated refund of the 24,000 XAF' } })
    fireEvent.click(screen.getByRole('button', { name: 'Resolve' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/it never marks a request paid/)).toBeTruthy()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Resolve' }))
    await waitFor(() => expect(resolve).toHaveBeenCalledWith('r1',
      { resolution: 'REFUND_SIMULATED', note: 'Simulated refund of the 24,000 XAF' }, expect.stringMatching(/^[0-9a-f-]{36}$/)))
  })

  it('lists the payment requests by status', async () => {
    vi.spyOn(paymentsApi, 'summary').mockResolvedValue({ demo: true, pending: 0, paid: 1, failed: 0, expired: 0, openExceptions: 0 })
    vi.spyOn(paymentsApi, 'requests').mockResolvedValue([{ id: 'p1', reference: 'DEMO-PAY-2026-00000001-X', applicationReference: 'DEMO-APP-2026-000001-X',
      amount: 25000, currency: 'XAF', status: 'PAID', createdAt: '2026-10-27T10:00:00Z', expiresAt: '2026-10-28T10:00:00Z',
      settledAt: '2026-10-27T10:00:02Z', transactionId: 'SIMTX-0002' }])
    render(<DialogProvider><FinancePage ctx={ctx()} params={new URLSearchParams({ view: 'requests', status: 'PAID' })} /></DialogProvider>)
    expect(await screen.findByText('DEMO-PAY-2026-00000001-X')).toBeTruthy()
    expect(paymentsApi.requests).toHaveBeenCalledWith('PAID', 0)
    expect(screen.getByText('SIMTX-0002')).toBeTruthy()
  })
})
