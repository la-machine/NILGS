// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { useEffect } from 'react'
import { afterEach, describe, expect, it } from 'vitest'
import { DialogProvider } from './Dialog'
import { useDialog } from './dialogContext'

const holder: { api?: ReturnType<typeof useDialog> } = {}

function Probe() {
  const dialog = useDialog()
  useEffect(() => { holder.api = dialog }, [dialog])
  return null
}

const reasonOptions = {
  title: 'Revoke a role', message: 'The role ends now.', label: 'Reason', minLength: 10, tooShort: 'At least {n} characters.',
  confirmLabel: 'Revoke', cancelLabel: 'Cancel', tone: 'danger' as const,
}

describe('dialogs', () => {
  afterEach(cleanup)

  it('ask for a reason, refuse one too short, and give back what was typed', async () => {
    render(<DialogProvider><Probe /></DialogProvider>)
    let answer: Promise<string | null>
    act(() => { answer = holder.api!.reason(reasonOptions) })
    expect(screen.getByText('The role ends now.')).toBeTruthy()
    fireEvent.change(screen.getByLabelText(/Reason/), { target: { value: 'short' } })
    fireEvent.click(screen.getByRole('button', { name: 'Revoke' }))
    expect(screen.getByText('At least 10 characters.')).toBeTruthy()
    fireEvent.change(screen.getByLabelText(/Reason/), { target: { value: '  Left the cadastral unit  ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Revoke' }))
    await expect(answer!).resolves.toBe('Left the cadastral unit')
  })

  it('answer null when cancelled, and false for a declined confirmation', async () => {
    render(<DialogProvider><Probe /></DialogProvider>)
    let reason: Promise<string | null>
    act(() => { reason = holder.api!.reason(reasonOptions) })
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    await expect(reason!).resolves.toBeNull()

    let ok: Promise<boolean>
    act(() => { ok = holder.api!.confirm({ title: 'Remove the key?', confirmLabel: 'Remove', cancelLabel: 'Keep' }) })
    fireEvent.click(screen.getByRole('button', { name: 'Keep' }))
    await expect(ok!).resolves.toBe(false)
  })
})
