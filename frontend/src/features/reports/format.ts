// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// A duration in whole seconds as a supervisor reads it: the two largest units, "3 d 4 h",
// "5 h 12 min", "8 min", "< 1 min".
import { fill } from '../../shared/i18n'
import type { Texts } from '../../shared/i18n'

export function formatDuration(texts: Texts, seconds: number): string {
  const u = texts.workload.units
  const minutes = Math.floor(Math.max(seconds, 0) / 60)
  if (minutes < 1) return u.lessThanMinute
  const days = Math.floor(minutes / 1440)
  const hours = Math.floor((minutes % 1440) / 60)
  const rest = minutes % 60
  if (days > 0) return hours > 0 ? `${fill(u.day, { n: days })} ${fill(u.hour, { n: hours })}` : fill(u.day, { n: days })
  if (hours > 0) return rest > 0 ? `${fill(u.hour, { n: hours })} ${fill(u.minute, { n: rest })}` : fill(u.hour, { n: hours })
  return fill(u.minute, { n: rest })
}
