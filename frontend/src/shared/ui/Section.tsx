// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// A titled card: the frame of every sign-in screen.
import type { ReactNode } from 'react'

export function Section({ title, intro, children }: { title: string; intro?: string; children: ReactNode }) {
  return (
    <section className="panel">
      <h2>{title}</h2>
      {intro && <p>{intro}</p>}
      {children}
    </section>
  )
}
