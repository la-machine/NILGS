// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The steps of a professional account, oldest first, as the database recorded them.
import type { Lang, Texts } from '../../shared/i18n'
import type { HistoryEntry } from './api'
import { eventLabel } from './tones'

export function History({ texts, lang, entries }: { texts: Texts; lang: Lang; entries: HistoryEntry[] }) {
  const p = texts.professionals
  if (entries.length === 0) return <p className="muted">{p.noHistory}</p>
  return (
    <ol className="plain-list exception-list" data-history>
      {entries.map((h, i) => (
        <li key={`${h.event}-${h.at}-${i}`} className="exception-item" data-event={h.event}>
          <div className="exception-head">
            <strong>{eventLabel(texts, h.event)}</strong>
            <span className="muted small num">{h.actorName ? `${h.actorName} · ` : ''}{new Date(h.at).toLocaleString(lang)}</span>
          </div>
          {h.reason && <p className="small">{h.reason}</p>}
        </li>
      ))}
    </ol>
  )
}
