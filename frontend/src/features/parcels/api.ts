// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Parcel endpoints (/api/v1/parcels, MVP-01). Creation sends an Idempotency-Key (ADR-013):
// the same key on a retry returns the first answer and creates nothing more.
import type { RaisedAlert } from '../conflicts'
import { call, query } from '../../shared/api/client'
import type { GeoJsonMultiPolygon, ProjectedGeometry } from '../maps'

export type Srid = 4326 | 32632 | 32633

export interface ParcelCreated {
  reference: string
  versionNo: number
  areaM2: number
  /** Overlap and duplicate alerts this version raised (MVP-04). */
  alerts: RaisedAlert[]
}

export interface ParcelVersion {
  versionNo: number
  geometry: GeoJsonMultiPolygon
  display: ProjectedGeometry
  areaM2: number
  sourceSrid: Srid
  locality: string
  externalReference: string | null
  verificationStatus: 'UNVERIFIED' | 'VERIFIED'
  changeReason: string | null
  createdBy: string
  createdAt: string
}

export interface ParcelDetail {
  reference: string
  officeCode: string
  status: 'ACTIVE' | 'SUPERSEDED' | 'RETIRED'
  currentVersion: number
  createdAt: string
  current: ParcelVersion
  versions: { versionNo: number; areaM2: number; sourceSrid: Srid; changeReason: string | null; createdAt: string }[]
}

export interface ParcelSummary {
  reference: string
  officeCode: string
  status: string
  currentVersion: number
  locality: string
  areaM2: number
  updatedAt: string
}

export interface NewParcel {
  officeCode: string
  locality: string
  externalReference?: string
  srid: Srid
  geometry: unknown
}

export interface Correction {
  expectedVersion: number
  srid: Srid
  geometry: unknown
  locality?: string
  externalReference?: string
  reason: string
}

export const parcelsApi = {
  create: (body: NewParcel, idempotencyKey: string) =>
    call<ParcelCreated>('POST', '/parcels', body, { 'Idempotency-Key': idempotencyKey }),
  correct: (reference: string, body: Correction) =>
    call<ParcelCreated>('POST', `/parcels/${encodeURIComponent(reference)}/versions`, body),
  read: (reference: string) => call<ParcelDetail>('GET', `/parcels/${encodeURIComponent(reference)}`),
  version: (reference: string, versionNo: number) =>
    call<ParcelVersion>('GET', `/parcels/${encodeURIComponent(reference)}/versions/${versionNo}`),
  search: (office: string, q: string, page = 0) =>
    call<ParcelSummary[]>('GET', `/parcels?${query({ office, q, page })}`),
}
