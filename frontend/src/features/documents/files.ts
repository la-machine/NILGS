// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// What the interface checks before sending a file, to save the person a wait: its size and its
// extension. The server checks the bytes themselves and decides.
import type { Documents } from './api'

/** The extensions the server accepts, for the file picker. */
export const ACCEPT = '.pdf,.jpg,.jpeg,.png,application/pdf,image/jpeg,image/png'

const EXTENSIONS = ['pdf', 'jpg', 'jpeg', 'png']

/** A problem code the server would also return, or null when the file may be sent. */
export function precheck(file: Pick<File, 'name' | 'size'>, documents: Pick<Documents, 'maxBytes'>): string | null {
  if (file.size === 0) return 'FILE_EMPTY'
  if (file.size > documents.maxBytes) return 'FILE_TOO_LARGE'
  const dot = file.name.lastIndexOf('.')
  if (dot <= 0 || !EXTENSIONS.includes(file.name.slice(dot + 1).toLowerCase())) return 'TYPE_NOT_ALLOWED'
  return null
}

/** A size for people: bytes, kB or MB, one decimal. */
export function readableSize(bytes: number, lang: 'fr' | 'en'): string {
  const unit = lang === 'fr' ? ['o', 'ko', 'Mo'] : ['B', 'kB', 'MB']
  if (bytes < 1024) return `${bytes} ${unit[0]}`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toLocaleString(lang, { maximumFractionDigits: 1 })} ${unit[1]}`
  return `${(bytes / (1024 * 1024)).toLocaleString(lang, { maximumFractionDigits: 1 })} ${unit[2]}`
}
