// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Amounts as the person reads them: whole units (XAF has no minor unit), grouped by the language.
import type { Lang } from '../../shared/i18n'

export function formatAmount(lang: Lang, amount: number, currency: string): string {
  return new Intl.NumberFormat(lang === 'fr' ? 'fr-FR' : 'en-GB', { style: 'currency', currency, maximumFractionDigits: 0 })
    .format(amount)
}
