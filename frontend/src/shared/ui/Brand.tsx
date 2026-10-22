// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The SNIGF logo (frontend/BRAND.md): the emblem in the header, the full logo above the
// sign-in and registration forms.

export function BrandTitle() {
  return (
    <h1 className="brand">
      <img src="/brand/snigf-emblem-96.png" width="48" height="48" alt="" />
      SNIGF / NILGS
    </h1>
  )
}

export function BrandLogo() {
  return (
    <img className="brand-logo" src="/brand/snigf-logo-480.png" width="240" height="242"
      alt="SNIGF — Système National Intégré de Gouvernance Foncière / National Integrated Land Governance System" />
  )
}
