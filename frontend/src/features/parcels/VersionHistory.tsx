// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The versions of a parcel as a timeline, newest first: when, the area and its change, the
// input system, and the reason given for each correction. Versions are never edited: a
// correction adds one, and any earlier one can be opened read-only.
import { formatArea } from '../maps'
import { fill } from '../../shared/i18n'
import type { Lang, Texts } from '../../shared/i18n'
import { Card } from '../../shared/ui/Card'
import { Badge } from '../../shared/ui/Feedback'
import type { ParcelDetail } from './api'

export function VersionHistory({ texts, lang, parcel, shown, onShow }: {
  texts: Texts; lang: Lang; parcel: ParcelDetail; shown: number; onShow: (versionNo: number) => void
}) {
  const p = texts.parcel
  const versions = [...parcel.versions].sort((a, b) => b.versionNo - a.versionNo)
  const byNo = new Map(parcel.versions.map((v) => [v.versionNo, v]))
  return (
    <Card title={texts.versionHistory} icon="history" description={p.historyIntro}>
      <ol className="timeline versions">
        {versions.map((v) => {
          const previous = byNo.get(v.versionNo - 1)
          const delta = previous ? v.areaM2 - previous.areaM2 : null
          const current = v.versionNo === parcel.currentVersion
          return (
            <li key={v.versionNo} className={v.versionNo === shown ? 'timeline-item active' : 'timeline-item'}>
              <span className="timeline-dot" aria-hidden="true" />
              <div className="timeline-body">
                <div className="timeline-head">
                  <button type="button" className="link-button strong" aria-current={v.versionNo === shown}
                    onClick={() => onShow(v.versionNo)}>
                    {fill(texts.versionLine, { n: v.versionNo, area: v.areaM2.toFixed(2), date: new Date(v.createdAt).toLocaleString(lang) })}
                  </button>
                  {current && <Badge tone="success">{p.current}</Badge>}
                  {v.versionNo === shown && !current && <Badge tone="warning">{p.viewing}</Badge>}
                </div>
                <p className="timeline-meta num">
                  {formatArea(v.areaM2)}
                  {delta !== null && Math.abs(delta) >= 0.005 && (
                    <span className={delta > 0 ? 'delta up' : 'delta down'}> ({delta > 0 ? '+' : '−'}{formatArea(Math.abs(delta))})</span>
                  )}
                  {' · '}EPSG:{v.sourceSrid}
                </p>
                {v.changeReason
                  ? <p className="timeline-reason">{v.changeReason}</p>
                  : v.versionNo === 1 && <p className="timeline-reason muted">{p.firstVersion}</p>}
              </div>
            </li>
          )
        })}
      </ol>
    </Card>
  )
}
