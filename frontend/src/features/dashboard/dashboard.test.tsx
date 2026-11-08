// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The dashboard's professional-account counts (ADR-041 D21): a tile for each count the server
// returns, linking to the directory filtered on its step, the administrator's own requests on an
// informational line, and nothing asked for by a reader only.
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { professionalApi } from '../professionals'
import { t } from '../../shared/i18n'
import type { PageContext, Session } from '../../shared/session/types'
import { DashboardPage } from './DashboardPage'

const texts = t('en')
const session = (permissions: string[]) => ({
  userId: 'me', displayName: 'Admin', accountType: 'STAFF', authLevel: 'PASSWORD', permissions, specialGrants: [], roles: [], grants: [],
}) as unknown as Session
const ctx = (s: Session) => ({ texts, lang: 'en', session: s, run: async (a: () => Promise<void>) => { await a() }, refreshSession: vi.fn() }) as unknown as PageContext
const READ = 'admin.professional.read'

afterEach(() => { cleanup(); vi.restoreAllMocks() })

describe('the professional-account counts on the dashboard (D21)', () => {
  it('shows a tile for each count returned, each opening the directory on its step', async () => {
    const counts = vi.spyOn(professionalApi, 'counts').mockResolvedValue({ requests: 3, activations: 1, changes: 0, waitingForOthers: 2 })
    render(<DashboardPage ctx={ctx(session([READ, 'admin.professional.verify', 'admin.professional.approve']))} />)
    const tile = async (label: string) => (await screen.findByText(label)).closest('a') as HTMLAnchorElement
    expect((await tile(texts.dash.pendingProfessionalRequests)).getAttribute('href')).toBe('#/professionals?pending=VERIFICATION')
    expect((await tile(texts.dash.pendingProfessionalActivations)).getAttribute('href')).toBe('#/professionals?pending=ACTIVATION')
    expect((await tile(texts.dash.pendingProfessionalChanges)).getAttribute('href')).toBe('#/professionals?pending=CHANGE')
    expect((await tile(texts.dash.pendingProfessionalRequests)).textContent).toContain('3')
    expect((await tile(texts.dash.pendingProfessionalChanges)).className).toBe('pending-tile')
    // One's own requests: a line for information, with no link to act on.
    const line = document.querySelector('[data-waiting-for-others]') as HTMLElement
    expect(line.textContent).toBe('2 of your professional account request(s) or verification(s) waiting for another administrator.')
    expect(line.querySelector('a')).toBeNull()
    expect(counts).toHaveBeenCalledTimes(1)
  })

  it('shows only the counts the server returned, never a zero for one left out', async () => {
    vi.spyOn(professionalApi, 'counts').mockResolvedValue({ activations: 2, changes: 1 })
    render(<DashboardPage ctx={ctx(session([READ, 'admin.professional.approve']))} />)
    await screen.findByText(texts.dash.pendingProfessionalActivations)
    expect(screen.queryByText(texts.dash.pendingProfessionalRequests)).toBeNull()
    expect(document.querySelector('[data-waiting-for-others]')).toBeNull()
  })

  it('says nothing waits when every count is zero, and still shows one\'s own requests', async () => {
    vi.spyOn(professionalApi, 'counts').mockResolvedValue({ requests: 0, waitingForOthers: 1 })
    render(<DashboardPage ctx={ctx(session([READ, 'admin.professional.verify']))} />)
    await screen.findByText(texts.dash.nothingWaiting)
    expect(document.querySelector('[data-waiting-for-others="1"]')).not.toBeNull()
  })

  it('asks nothing for a reader only, nor for someone without the directory', async () => {
    const counts = vi.spyOn(professionalApi, 'counts').mockResolvedValue({})
    render(<DashboardPage ctx={ctx(session([READ]))} />)
    render(<DashboardPage ctx={ctx(session(['admin.professional.approve']))} />)
    await waitFor(() => expect(screen.getAllByText(texts.dash.start)).toHaveLength(2))
    expect(counts).not.toHaveBeenCalled()
    expect(screen.queryByText(texts.dash.attention)).toBeNull()
  })
})
