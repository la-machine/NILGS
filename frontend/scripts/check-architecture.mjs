// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Keeps the front end's structure (src/README.md) as it was designed, the way
// DddLayeringTest does for the back end. Run by `npm run lint`; fails naming each breach.
//
//   shared/    imports nothing from app/ or features/ (it is the bottom layer)
//   features/  import shared/, their own files, and another feature only through its
//              index.ts (its public face); never app/
//   app/       imports features only through their index.ts
//   every file in src/ starts with the FLYT / Pythagore signature
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const SRC = resolve(dirname(fileURLToPath(import.meta.url)), '..', 'src')
const CODE = /\.(ts|tsx)$/
const SPECIFIER = /(?:import|export)\s[^'"]*?from\s*['"]([^'"]+)['"]|import\s*\(\s*['"]([^'"]+)['"]\s*\)|import\s+['"]([^'"]+)['"]/g

function files(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    return statSync(path).isDirectory() ? files(path) : [path]
  })
}

/** "features/roles/api" -> { layer: 'features', feature: 'roles', rest: 'api' } */
function place(pathFromSrc) {
  const parts = pathFromSrc.split('/')
  return { layer: parts[0], feature: parts[0] === 'features' ? parts[1] : undefined, rest: parts.slice(2).join('/') }
}

const problems = []
for (const file of files(SRC)) {
  const from = relative(SRC, file).split(sep).join('/')
  const text = readFileSync(file, 'utf8')
  if (/\.(ts|tsx|css)$/.test(file) && !/^(\/\/|\/\*) SNIGF\/NILGS - Copyright \(c\) 2026 FLYT\./.test(text)) {
    problems.push(`${from}: missing the signature header`)
  }
  if (!CODE.test(file)) continue
  const source = place(from)
  for (const match of text.matchAll(SPECIFIER)) {
    const spec = match[1] ?? match[2] ?? match[3]
    if (!spec.startsWith('.')) continue
    const target = relative(SRC, resolve(dirname(file), spec)).split(sep).join('/')
    if (target.startsWith('..')) {
      problems.push(`${from}: imports ${spec}, outside src/`)
      continue
    }
    const to = place(target)
    if (source.layer === 'shared' && (to.layer === 'app' || to.layer === 'features')) {
      problems.push(`${from}: shared code must not import ${to.layer}/ (${spec})`)
    }
    if (source.layer === 'features' && to.layer === 'app') {
      problems.push(`${from}: a feature must not import the app shell (${spec})`)
    }
    const crossing = to.layer === 'features' && (source.layer !== 'features' || source.feature !== to.feature)
    if (crossing && to.rest !== '' && to.rest !== 'index') {
      problems.push(`${from}: import features/${to.feature} through its index.ts, not ${spec}`)
    }
  }
}

for (const feature of readdirSync(join(SRC, 'features'))) {
  for (const required of ['index.ts', 'api.ts']) {
    try {
      statSync(join(SRC, 'features', feature, required))
    } catch {
      problems.push(`features/${feature}: missing ${required}`)
    }
  }
}

if (problems.length > 0) {
  console.error('Front-end structure check failed (src/README.md):\n - ' + problems.join('\n - '))
  process.exit(1)
}
console.log('Front-end structure check passed.')
