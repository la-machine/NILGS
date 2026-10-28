// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Navigation by URL fragment (#/parcels/REF?view=satellite): bookmarkable, survives a reload,
// keeps the browser's back button, and needs neither a library nor a server rewrite rule
// (the proxy serves one page). A route only chooses what to show: every call is still
// authorized by the server.
import { useSyncExternalStore } from 'react'

export interface Route {
  /** Path segments, decoded: '#/parcels/DEMO-PCL-…' -> ['parcels', 'DEMO-PCL-…']. */
  segments: string[]
  params: URLSearchParams
}

export function parse(hash: string): Route {
  const raw = hash.replace(/^#\/?/, '')
  const [path, search = ''] = raw.split('?')
  const segments = path.split('/').filter(Boolean).map((s) => {
    try {
      return decodeURIComponent(s)
    } catch {
      return s
    }
  })
  return { segments, params: new URLSearchParams(search) }
}

/** '#/' + segments (encoded) + query string of the defined values. */
export function href(segments: string[], params: Record<string, string | undefined> = {}): string {
  const path = segments.map(encodeURIComponent).join('/')
  const query = new URLSearchParams(Object.entries(params).filter((e): e is [string, string] => !!e[1])).toString()
  return `#/${path}${query ? `?${query}` : ''}`
}

export function navigate(to: string, options: { replace?: boolean } = {}) {
  if (options.replace) {
    window.history.replaceState(null, '', to)
    window.dispatchEvent(new HashChangeEvent('hashchange'))
  } else {
    window.location.hash = to
  }
}

function subscribe(onChange: () => void) {
  window.addEventListener('hashchange', onChange)
  return () => window.removeEventListener('hashchange', onChange)
}

const snapshot = () => window.location.hash

export function useRoute(): Route {
  const hash = useSyncExternalStore(subscribe, snapshot, () => '')
  return parse(hash)
}
