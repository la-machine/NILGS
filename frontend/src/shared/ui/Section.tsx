// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The card of every sign-in screen (sign-in, registration, security keys, recovery).
import type { ReactNode } from 'react'

export function Section({ title, intro, children }: { title: string; intro?: string; children: ReactNode }) {
  return (
    <section className="auth-card">
      <h2>{title}</h2>
      {intro && <p className="auth-intro">{intro}</p>}
      {children}
    </section>
  )
}
