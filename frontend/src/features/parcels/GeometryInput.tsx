// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The boundary of a parcel as the officer supplies it: the coordinate system, chosen in plain
// words, then GeoJSON pasted or read from a file (.geojson, .json). As it is typed, the field
// says what it sees (shape, vertices, closed rings, whether the numbers look like the chosen
// system). It never changes the text: what is sent is what was typed, and PostGIS validates
// on the server; its reason is shown here if it refuses.
import { useId, useState } from 'react'
import type { ChangeEvent } from 'react'
import type { GeometryFacts } from '../maps'
import { fill } from '../../shared/i18n'
import type { Texts } from '../../shared/i18n'
import { Icon } from '../../shared/ui/Icon'
import type { Srid } from './api'
import { readGeometry } from './geometry'
import type { GeometryValue } from './geometry'

export function GeometryInput({ texts, value, onChange, error, detail }: {
  texts: Texts; value: GeometryValue; onChange: (value: GeometryValue) => void
  /** The server's refusal, shown next to the text. */
  error?: string | null; detail?: string
}) {
  const id = useId()
  const [fileName, setFileName] = useState<string | null>(null)
  const [fileProblem, setFileProblem] = useState<string | null>(null)
  const p = texts.parcel
  const reading = readGeometry(value)
  const errors: Record<string, string> = texts.errors

  const readFile = (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    if (file.size > 2_000_000) {
      setFileName(null)
      setFileProblem(p.fileTooLarge)
      return
    }
    setFileProblem(null)
    void file.text().then((text) => {
      setFileName(file.name)
      onChange({ ...value, text })
    })
  }

  const systems: { srid: Srid; title: string; text: string }[] = [
    { srid: 4326, title: p.crs4326Title, text: p.crs4326Text },
    { srid: 32632, title: p.crs32632Title, text: p.crs32632Text },
    { srid: 32633, title: p.crs32633Title, text: p.crs32633Text },
  ]

  const localProblem = reading.state === 'error' ? errors[reading.code] : reading.state === 'unsupported' ? errors.GEOMETRY_TYPE_UNSUPPORTED : null

  return (
    <div className="geometry-input">
      <fieldset className="choice-group">
        <legend className="field-label">{texts.crs}</legend>
        <p className="field-hint">{p.crsHelp}</p>
        <div className="choice-cards">
          {systems.map((s) => (
            <label key={s.srid} className={value.srid === s.srid ? 'choice-card selected' : 'choice-card'}>
              <input type="radio" name={`${id}-crs`} value={s.srid} checked={value.srid === s.srid}
                onChange={() => onChange({ ...value, srid: s.srid })} />
              <span>
                <span className="choice-title">{s.title}</span>
                <span className="choice-text">{s.text}</span>
                <span className="choice-code">EPSG:{s.srid}</span>
              </span>
            </label>
          ))}
        </div>
      </fieldset>

      <div className="field">
        <label htmlFor={`${id}-file`} className="field-label">{texts.geoJsonFile}</label>
        <div className="file-input">
          <Icon name="upload" size={18} />
          <input id={`${id}-file`} type="file" accept=".geojson,.json,application/geo+json,application/json" onChange={readFile} />
        </div>
        {fileName && <p className="field-hint"><Icon name="file" size={14} /> {fill(p.fileLoaded, { name: fileName })}</p>}
        {fileProblem && <p className="field-error">{fileProblem}</p>}
      </div>

      <div className="field">
        <label htmlFor={`${id}-text`} className="field-label">
          {texts.geoJsonText}<span className="field-required" aria-hidden="true"> *</span>
        </label>
        <textarea id={`${id}-text`} required rows={7} spellCheck={false} className="mono" value={value.text}
          aria-invalid={error || localProblem ? true : undefined}
          aria-describedby={`${id}-hint ${id}-check`}
          onChange={(e) => onChange({ ...value, text: e.target.value })} />
        <p id={`${id}-hint`} className="field-hint">{texts.geometryHint}</p>
        <div id={`${id}-check`} aria-live="polite">
          {error && (
            <div className="field-error-block">
              <p className="field-error">{error}</p>
              {detail && <p className="field-error-detail">{fill(p.serverReason, { reason: detail })}</p>}
            </div>
          )}
          {!error && localProblem && <p className="field-error">{localProblem}</p>}
          {reading.state === 'ok' && <Facts texts={texts} facts={reading.facts} />}
        </div>
      </div>
    </div>
  )
}

function Facts({ texts, facts }: { texts: Texts; facts: GeometryFacts }) {
  const p = texts.parcel
  return (
    <ul className="facts">
      <li className="fact-ok"><Icon name="check" size={14} />
        {fill(p.factShape, { type: facts.type, polygons: facts.polygons, vertices: facts.vertices })}
      </li>
      {facts.openRings > 0
        ? <li className="fact-warn"><Icon name="alert" size={14} />{fill(p.factOpen, { n: facts.openRings })}</li>
        : <li className="fact-ok"><Icon name="check" size={14} />{p.factClosed}</li>}
      {facts.unitMismatch && <li className="fact-warn"><Icon name="alert" size={14} />{p.factUnits}</li>}
    </ul>
  )
}
