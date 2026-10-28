// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The interface's icons, drawn for this project on one 24-pixel grid with one stroke width,
// so every icon has the same weight and no icon library is needed. Decorative by default
// (hidden from assistive technology); give `label` when the icon is the only content.

const PATHS = {
  dashboard: 'M4 4h6.5v6.5H4zM13.5 4H20v6.5h-6.5zM4 13.5h6.5V20H4zM13.5 13.5H20V20h-6.5z',
  parcel: 'M4 8.5L10 4.5l9 3 1 9-8 4-8-4z',
  plus: 'M12 5v14M5 12h14',
  minus: 'M5 12h14',
  search: 'M10.5 17.5a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM15.5 15.5L21 21',
  users: 'M9 11.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7zM2.5 20c0-3.6 2.9-6 6.5-6s6.5 2.4 6.5 6M16 4.6a3.5 3.5 0 0 1 0 6.8M18 14.3c2 .7 3.5 2.6 3.5 5.7',
  shield: 'M12 3l7 3v6c0 4.5-3 7.6-7 9-4-1.4-7-4.5-7-9V6zM9 12l2 2 4-4',
  key: 'M8 19a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM10.9 12.1L20 3M16 7l2.5 2.5M13.5 9.5l2 2',
  userCheck: 'M9 11.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7zM2.5 20c0-3.6 2.9-6 6.5-6 1.6 0 3 .5 4.1 1.3M15.5 18l2 2 4-4.5',
  audit: 'M9 6h11M9 12h11M9 18h11M4.5 5.2l.8.8 1.5-1.6M4.5 11.2l.8.8 1.5-1.6M4.5 17.2l.8.8 1.5-1.6',
  user: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 21c0-4.4 3.6-7 8-7s8 2.6 8 7',
  logout: 'M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3M10 16l-4-4 4-4M6 12h10',
  globe: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM3 12h18M12 3c2.5 2.5 3.8 5.5 3.8 9s-1.3 6.5-3.8 9c-2.5-2.5-3.8-5.5-3.8-9S9.5 5.5 12 3z',
  menu: 'M4 6.5h16M4 12h16M4 17.5h16',
  close: 'M6 6l12 12M18 6L6 18',
  chevronRight: 'M9.5 6l6 6-6 6',
  chevronLeft: 'M14.5 6l-6 6 6 6',
  chevronDown: 'M6 9.5l6 6 6-6',
  alert: 'M12 4L2.8 20h18.4zM12 10v4.5M12 17.4v.1',
  info: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 11v5.5M12 7.6v.1',
  check: 'M5 12.5l4.5 4.5L19 7.5',
  checkCircle: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM8 12.3l2.8 2.8L16.2 9.5',
  xCircle: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM9 9l6 6M15 9l-6 6',
  map: 'M3 6.5L9 4l6 2.5L21 4v13.5L15 20l-6-2.5L3 20zM9 4v13.5M15 6.5V20',
  satellite: 'M7.5 10.5l3-3 6 6-3 3zM4.5 7.5l3-3 2 2-3 3zM14.5 17.5l3-3 2 2-3 3zM10 14l-3 3M16.5 3a4.5 4.5 0 0 1 4.5 4.5M16.5 6a1.5 1.5 0 0 1 1.5 1.5',
  technical: 'M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM3 9h18M3 15h18M9 3v18M15 3v18',
  ruler: 'M3.5 16.5L16.5 3.5l4 4-13 13zM7.5 12.5l2 2M10.5 9.5l2 2M13.5 6.5l2 2',
  fit: 'M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5',
  offline: 'M3 3l18 18M8.5 16.5a5 5 0 0 1 7 0M5 12.6a10 10 0 0 1 4-2.4M19 12.6a10 10 0 0 0-3.1-2.1M2 8.8a15 15 0 0 1 4.2-2.6M22 8.8a15 15 0 0 0-9-3.7M12 20v.1',
  server: 'M5 4h14a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM5 13h14a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2zM7 7.5v.1M7 16.5v.1',
  history: 'M12 21a9 9 0 1 0-8.5-6M3 20v-5h5M12 7.5V12l3 2',
  edit: 'M4 20h4L19 9l-4-4L4 16zM13.5 6.5l4 4',
  refresh: 'M20 11a8 8 0 0 0-14.4-4.7L4 8M4 4v4h4M4 13a8 8 0 0 0 14.4 4.7L20 16M20 20v-4h-4',
  upload: 'M12 15V4M7.5 8.5L12 4l4.5 4.5M4 15v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3',
  download: 'M12 4v11M7.5 10.5L12 15l4.5-4.5M4 17v1a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-1',
  lock: 'M7 11h10a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2zM8 11V7a4 4 0 0 1 8 0v4',
  office: 'M4 21V5.5L12 3v18M12 9l8 2.2V21M2.5 21h19M7.5 8h1M7.5 12h1M7.5 16h1M15.5 13.5h1M15.5 17h1',
  layers: 'M12 3l9 5-9 5-9-5zM3 13l9 5 9-5',
  eye: 'M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z',
  arrowLeft: 'M19 12H5M11 6l-6 6 6 6',
  arrowRight: 'M5 12h14M13 6l6 6-6 6',
  crosshair: 'M12 20a8 8 0 1 0 0-16 8 8 0 0 0 0 16zM12 2v4M12 18v4M2 12h4M18 12h4',
  file: 'M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9zM14 3v6h6',
  trash: 'M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13',
  clock: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 7v5l3 2',
  signature: 'M3 17c2.5 0 3.5-9 6-9s1.5 8 4 8 2-4 4-4 1.5 2 4 2M3 21h18',
  card: 'M3 6.5h18v11H3zM3 10h18M6.5 14.5h4',
} as const

export type IconName = keyof typeof PATHS

export function Icon({ name, size = 20, label, className }: {
  name: IconName; size?: number; label?: string; className?: string
}) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75}
      strokeLinecap="round" strokeLinejoin="round" className={className ? `icon ${className}` : 'icon'}
      role={label ? 'img' : undefined} aria-label={label} aria-hidden={label ? undefined : true} focusable="false">
      <path d={PATHS[name]} />
    </svg>
  )
}
