// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Browsers compile an input's `pattern` with the `v` flag; an invalid one is silently
// ignored, and the field is then not checked at all. Every pattern in the interface must
// compile that way.
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    return statSync(path).isDirectory() ? sources(path) : /\.tsx$/.test(name) && !/\.test\./.test(name) ? [path] : []
  })
}

describe('input patterns', () => {
  it('all compile as browsers compile them (v flag)', () => {
    const patterns = sources(join(__dirname, '..')).flatMap((file) =>
      [...readFileSync(file, 'utf8').matchAll(/pattern="([^"]+)"/g)].map((m) => ({ file, pattern: m[1] })))
    expect(patterns.length).toBeGreaterThan(2)
    for (const { file, pattern } of patterns) {
      expect(() => new RegExp(`^(?:${pattern})$`, 'v'), `${file}: ${pattern}`).not.toThrow()
    }
    const keyLabel = patterns.find((p) => p.pattern.includes('\\p{L}'))!.pattern
    expect(new RegExp(`^(?:${keyLabel})$`, 'v').test("Clé USB bleue (bureau) - n'1")).toBe(true)
    expect(new RegExp(`^(?:${keyLabel})$`, 'v').test('<script>')).toBe(false)
  })
})
