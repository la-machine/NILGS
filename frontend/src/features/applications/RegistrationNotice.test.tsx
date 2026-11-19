// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { t } from '../../shared/i18n'
import { RegistrationNotice } from './RegistrationNotice'

describe('the applicant\'s registration (ADR-042 §4)', () => {
  afterEach(() => cleanup())

  it('names the title once registered, and says it is not a land title', () => {
    render(<RegistrationNotice texts={t('en')} lang="en" registration={{ titleNumber: 'DEMO-TF-DEMO1-000007-H', registeredAt: '2026-11-14T10:00:00Z' }} />)
    expect(screen.getByText('Registered: DEMO-TF-DEMO1-000007-H')).toBeTruthy()
    expect(screen.getByText(/not a land title and not a certificate of ownership/)).toBeTruthy()
  })

  it('shows nothing before', () => {
    const { container } = render(<RegistrationNotice texts={t('fr')} lang="fr" registration={null} />)
    expect(container.textContent).toBe('')
  })
})

describe('the applicant\'s transfer (ADR-042 step 6)', () => {
  afterEach(() => cleanup())

  it('says the transfer is registered on the title the case names, and that it is not a land title', () => {
    const { container } = render(<RegistrationNotice texts={t('en')} lang="en"
      registration={{ titleNumber: 'DEMO-TF-DEMO1-000003-D', registeredAt: '2026-11-16T10:00:00Z', kind: 'TRANSFER' }} />)
    expect(screen.getByText('Transfer registered: DEMO-TF-DEMO1-000003-D')).toBeTruthy()
    expect(screen.getByText(/not a land title and not a certificate of ownership/)).toBeTruthy()
    expect(container.querySelector('[data-registration="TRANSFER"]')).toBeTruthy()
  })
})
