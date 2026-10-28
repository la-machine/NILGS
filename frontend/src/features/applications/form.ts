// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The form of an application type, read in the person's language, and the server's items
// (MISSING:statement, TOO_LONG:representativeName, PARCEL_NOT_FOUND ...) turned into a message
// next to the field concerned and in the summary above the form. Display only: the server checks.
import { fill } from '../../shared/i18n'
import type { Lang, Texts } from '../../shared/i18n'
import type { ApplicationType, FieldSpec } from './api'

/** The key of the parcel reference in the items and in the field messages. */
export const PARCEL_FIELD = 'parcelReference'

export function label(lang: Lang, thing: { labelFr: string; labelEn: string }): string {
  return lang === 'fr' ? thing.labelFr : thing.labelEn
}

export function hint(lang: Lang, field: FieldSpec): string | null {
  return lang === 'fr' ? field.hintFr : field.hintEn
}

export function typeName(lang: Lang, type: Pick<ApplicationType, 'nameFr' | 'nameEn'>): string {
  return lang === 'fr' ? type.nameFr : type.nameEn
}

export interface ItemMessage { field: string | null; text: string }

/** One readable message per server item, tied to its field when there is one. */
export function messages(texts: Texts, lang: Lang, type: ApplicationType | null, items: string[]): ItemMessage[] {
  const a = texts.applications
  const fieldLabel = (key: string) => {
    if (key === PARCEL_FIELD) return a.parcelReference
    const field = type?.requirements.fields.find((f) => f.key === key)
    return field ? label(lang, field) : key
  }
  return items.map((item) => {
    const [code, key] = item.split(':', 2)
    switch (code) {
      case 'MISSING': return { field: key, text: fill(a.items.MISSING, { field: fieldLabel(key) }) }
      case 'MISSING_DOCUMENT': return { field: null, text: fill(a.items.MISSING_DOCUMENT, { document: documentLabel(lang, type, key) }) }
      case 'TOO_LONG': return { field: key, text: fill(a.items.TOO_LONG, { field: fieldLabel(key) }) }
      case 'NOT_AN_OPTION': return { field: key, text: fill(a.items.NOT_AN_OPTION, { field: fieldLabel(key) }) }
      case 'UNKNOWN_FIELD': return { field: null, text: fill(a.items.UNKNOWN_FIELD, { field: key }) }
      case 'PARCEL_NOT_FOUND': return { field: PARCEL_FIELD, text: a.items.PARCEL_NOT_FOUND }
      case 'PARCEL_REFERENCE_INVALID': return { field: PARCEL_FIELD, text: a.items.PARCEL_REFERENCE_INVALID }
      default: return { field: null, text: item }
    }
  })
}

/** A document category's label in the person's language; its code when the type does not know it. */
export function documentLabel(lang: Lang, type: ApplicationType | null, code: string): string {
  const category = type?.requirements.documents.find((d) => d.code === code)
  return category ? label(lang, category) : code
}

/** The values to send: trimmed, empty ones left out. */
export function cleaned(values: Record<string, string>): Record<string, string> {
  return Object.fromEntries(Object.entries(values).map(([k, v]) => [k, v.trim()]).filter(([, v]) => v !== ''))
}
