// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Overlap and duplicate alerts (MVP-04, ADR-030): raised by the server as each parcel version
// is stored (E-10); reviewed by office and resolved here (E-11). The server decides who may
// read or resolve; a resolution by the author of a compared version is refused there.
import { call } from '../../shared/api/client'
import type { GeoJsonMultiPolygon } from '../maps'

export type AlertType = 'OVERLAP' | 'DUPLICATE_GEOMETRY' | 'DUPLICATE_REFERENCE'
export type AlertStatus = 'OPEN' | 'RESOLVED' | 'SUPERSEDED'
export type Resolution = 'NO_CONFLICT' | 'DATA_ERROR_CORRECTED' | 'REFERRED_FOR_ADJUDICATION'

export const ALERT_TYPES: AlertType[] = ['OVERLAP', 'DUPLICATE_GEOMETRY', 'DUPLICATE_REFERENCE']
export const ALERT_STATUSES: AlertStatus[] = ['OPEN', 'RESOLVED', 'SUPERSEDED']
export const RESOLUTIONS: Resolution[] = ['NO_CONFLICT', 'DATA_ERROR_CORRECTED', 'REFERRED_FOR_ADJUDICATION']

/** Bounds of a resolution note, as the server checks them. */
export const NOTE_MIN = 10
export const NOTE_MAX = 2000

/** An alert raised by the version just written, as the creation or correction answers it. */
export interface RaisedAlert {
  id: string
  type: AlertType
  otherReference: string
  intersectionM2: number | null
  shareOfThisParcel: number | null
}

export interface ParcelAlert {
  id: string
  type: AlertType
  status: AlertStatus
  otherReference: string
  otherOfficeCode: string
  thisVersionNo: number
  otherVersionNo: number
  intersectionM2: number | null
  shareOfThisParcel: number | null
  shareOfOtherParcel: number | null
  /** The intersection, EPSG:4326 MultiPolygon; null for a duplicate reference. */
  intersection: GeoJsonMultiPolygon | null
  normalizedReference: string | null
  raisedAt: string
  resolution: Resolution | null
  resolutionNote: string | null
  resolvedAt: string | null
  supersededAt: string | null
}

/** One parcel of the pair, as compared. */
export interface AlertSide {
  reference: string
  officeCode: string
  versionNo: number
  currentVersionNo: number
  authorName: string
  areaM2: number
  /** Share of this parcel the intersection covers; null for a duplicate reference. */
  share: number | null
}

export interface AlertSummary {
  id: string
  type: AlertType
  status: AlertStatus
  a: AlertSide
  b: AlertSide
  intersectionM2: number | null
  normalizedReference: string | null
  raisedAt: string
  resolution: Resolution | null
  resolvedAt: string | null
  supersededAt: string | null
}

export interface AlertDetail extends AlertSummary {
  /** Optimistic lock: a resolution names the version it was decided on. */
  version: number
  /** Both compared versions and the intersection in one UTM zone (metres), for the review map. */
  display: { srid: number; a: GeoJsonMultiPolygon; b: GeoJsonMultiPolygon; intersection: GeoJsonMultiPolygon | null } | null
  raisedByReference: string
  resolutionNote: string | null
  resolvedByName: string | null
  /** The person asking wrote a compared version: they may review, not resolve. */
  resolverIsAuthor: boolean
}

export const conflictsApi = {
  ofParcel: (reference: string) => call<ParcelAlert[]>('GET', `/parcels/${encodeURIComponent(reference)}/alerts`),
  list: (office: string, status: AlertStatus | '', type: AlertType | '', page: number) => {
    const query = new URLSearchParams({ office, page: String(page) })
    if (status) query.set('status', status)
    if (type) query.set('type', type)
    return call<AlertSummary[]>('GET', `/alerts?${query}`)
  },
  read: (id: string) => call<AlertDetail>('GET', `/alerts/${encodeURIComponent(id)}`),
  resolve: (id: string, body: { expectedVersion: number; resolution: Resolution; note: string }) =>
    call<AlertDetail>('POST', `/alerts/${encodeURIComponent(id)}/resolution`, body),
}
