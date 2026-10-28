// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { t } from '../../shared/i18n'
import { paymentsApi } from './api'
import type { PaymentRequest, PaymentView } from './api'
import { formatAmount } from './format'
import { PaymentPanel } from './PaymentPanel'

const fee = { amount: 25000, currency: 'XAF', labelFr: 'Frais DÉMO', labelEn: 'DEMO platform service charge — not an official administrative fee' }
const request = (status: PaymentRequest['status']): PaymentRequest => ({
  id: 'p1', reference: 'DEMO-PAY-2026-00000001-X', amount: 25000, currency: 'XAF', status, scenario: 'SUCCEED',
  createdAt: '2026-10-27T10:00:00Z', expiresAt: '2026-10-28T10:00:00Z', settledAt: status === 'PENDING' ? null : '2026-10-27T10:00:02Z',
})
const due: PaymentView = { applicationId: 'a1', demo: true, fee, requiredBeforeAcceptance: true, standing: 'DUE', current: null, history: [] }

describe('the applicant\'s DEMO payment', () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks() })

  it('says no real money moves, shows the DEMO fee and pays once with an idempotency key', async () => {
    vi.spyOn(paymentsApi, 'view').mockResolvedValue(due)
    const paid: PaymentView = { ...due, standing: 'PAID', current: request('PAID'), history: [request('PAID')] }
    const pay = vi.spyOn(paymentsApi, 'pay').mockResolvedValue(paid)
    render(<PaymentPanel texts={t('en')} lang="en" applicationId="a1" />)
    expect(await screen.findByText('Demonstration: no real money moves')).toBeTruthy()
    expect(screen.getByText('DEMO platform service charge — not an official administrative fee')).toBeTruthy()
    expect(screen.getByText('Waits for this payment.')).toBeTruthy()
    fireEvent.change(screen.getByLabelText(/^What the DEMO provider will do/), { target: { value: 'FAIL' } })
    fireEvent.click(screen.getByRole('button', { name: `Pay ${formatAmount('en', 25000, 'XAF')} (simulated)` }))
    await waitFor(() => expect(pay).toHaveBeenCalledWith('a1', 'FAIL', expect.stringMatching(/^[0-9a-f-]{36}$/)))
    expect(await screen.findByText('Paid. The office can now accept the application as complete.')).toBeTruthy()
  })

  it('offers to pay again after a failure, and nothing when no fee applies', async () => {
    vi.spyOn(paymentsApi, 'view').mockResolvedValue({ ...due, current: request('FAILED'), history: [request('FAILED')] })
    const { unmount } = render(<PaymentPanel texts={t('en')} lang="en" applicationId="a1" />)
    expect(await screen.findByText('The last payment was refused. You can pay again.')).toBeTruthy()
    unmount()
    vi.spyOn(paymentsApi, 'view').mockResolvedValue({ ...due, fee: null, standing: 'NOT_REQUIRED' })
    const { container } = render(<PaymentPanel texts={t('en')} lang="en" applicationId="a1" />)
    await waitFor(() => expect(paymentsApi.view).toHaveBeenCalled())
    await waitFor(() => expect(container.textContent).toBe(''))
  })

  it('waits for the provider while a payment is pending', async () => {
    vi.spyOn(paymentsApi, 'view').mockResolvedValue({ ...due, standing: 'PENDING', current: request('PENDING'), history: [request('PENDING')] })
    render(<PaymentPanel texts={t('en')} lang="en" applicationId="a1" />)
    expect(await screen.findByText('Waiting for the DEMO provider')).toBeTruthy()
    expect(screen.queryByRole('button', { name: /^Pay / })).toBeNull()
    expect(screen.getByText('DEMO-PAY-2026-00000001-X')).toBeTruthy()
  })
})
