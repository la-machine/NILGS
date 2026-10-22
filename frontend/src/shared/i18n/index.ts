// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Texts of the interface in French (default) and English. Screens receive `texts` and
// never hard-code a sentence; `fill` replaces {placeholders}.
import { en } from './en'
import { fr } from './fr'
import type { Texts } from './fr'

export type { Texts }
export type Lang = 'fr' | 'en'

export function t(lang: Lang): Texts {
  return lang === 'fr' ? fr : en
}

export function fill(template: string, values: Record<string, string | number>): string {
  return template.replace(/\{(\w+)\}/g, (_, key: string) => String(values[key] ?? ''))
}
