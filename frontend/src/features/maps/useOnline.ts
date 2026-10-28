// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Whether the browser has a network connection, as it reports it, so the tiled views can say
// "offline" instead of failing while the technical view keeps working.
import { useSyncExternalStore } from 'react'

function subscribe(onChange: () => void) {
  window.addEventListener('online', onChange)
  window.addEventListener('offline', onChange)
  return () => {
    window.removeEventListener('online', onChange)
    window.removeEventListener('offline', onChange)
  }
}

export function useOnline(): boolean {
  return useSyncExternalStore(subscribe, () => navigator.onLine, () => true)
}
