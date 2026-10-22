// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The application shell: which screen to show (the server decides the next step), one
// place that runs actions with a busy state and turns errors into messages, and the
// back-office panels once signed in. Screens and panels live in their features; this file
// only wires them. Routing, layouts and the business screens arrive with E-07.
import { useCallback, useEffect, useState } from 'react'
import {
  authApi, createCredential, EnrolScreen, getAssertion, LoginScreen, RecoveryCodesScreen, RecoveryScreen,
  RegisterScreen, SessionScreen, VerifyScreen,
} from '../features/auth'
import { ApiError } from '../shared/api/client'
import { fill, t } from '../shared/i18n'
import type { Lang } from '../shared/i18n'
import type { NextStep } from '../shared/session/types'
import { BrandLogo } from '../shared/ui/Brand'
import { errorMessage } from '../shared/ui/errorMessage'
import { Layout } from './Layout'
import { Panels } from './Panels'
import type { Screen } from './screens'

export function App() {
  const [lang, setLang] = useState<Lang>('fr')
  const [screen, setScreen] = useState<Screen>({ name: 'loading' })
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const texts = t(lang)

  /** Shows the screen the server says comes next. */
  const follow = useCallback(async (next: NextStep, notice?: string) => {
    if (next === 'MFA_ENROLMENT') setScreen({ name: 'enrol', registered: 0, required: 0 })
    else if (next === 'MFA_CHALLENGE') setScreen({ name: 'verify' })
    else setScreen({ name: 'home', session: await authApi.session(), notice })
  }, [])

  useEffect(() => {
    authApi.session()
      .then((session) => follow(session.nextStep))
      .catch(() => setScreen({ name: 'login' }))
  }, [follow])

  /** Runs an action with a busy state; errors become a message, never a crash. */
  const run = async (action: () => Promise<void>) => {
    setBusy(true)
    setError(null)
    try {
      await action()
    } catch (e) {
      if (e instanceof ApiError && e.code === 'AUTH_REQUIRED') setScreen({ name: 'login' })
      setError(errorMessage(texts, e))
    } finally {
      setBusy(false)
    }
  }

  const signOut = () => run(async () => {
    await authApi.logout()
    setScreen({ name: 'login' })
  })

  const registerKey = async (label: string) => {
    const credential = await createCredential(await authApi.registrationOptions())
    return authApi.registerKey(credential, label)
  }

  return (
    <Layout lang={lang} onToggleLang={() => setLang(lang === 'fr' ? 'en' : 'fr')}>
      {(screen.name === 'login' || screen.name === 'register') && <BrandLogo />}
      {error && <p className="error" role="alert">{error}</p>}
      {screen.name === 'login' && (
        <LoginScreen texts={texts} busy={busy}
          onSubmit={(email, password) => run(async () => follow((await authApi.login(email, password)).nextStep))}
          onRegister={() => { setError(null); setScreen({ name: 'register' }) }} />
      )}
      {screen.name === 'register' && (
        <RegisterScreen texts={texts} busy={busy} lang={lang}
          onSubmit={(body) => run(async () => {
            await authApi.register(body)
            setScreen({ name: 'login' })
            setError(texts.registered)
          })}
          onBack={() => { setError(null); setScreen({ name: 'login' }) }} />
      )}
      {screen.name === 'enrol' && (
        <EnrolScreen texts={texts} busy={busy} registered={screen.registered} required={screen.required}
          onRegister={(label) => run(async () => {
            const result = await registerKey(label)
            if (result.recoveryCodes) setScreen({ name: 'codes', codes: result.recoveryCodes })
            else if (result.nextStep === 'DONE') await follow('DONE')
            else setScreen({ name: 'enrol', registered: result.registered, required: result.required })
          })}
          onSignOut={signOut} />
      )}
      {screen.name === 'verify' && (
        <VerifyScreen texts={texts} busy={busy}
          onProve={() => run(async () => {
            await authApi.proveKey(await getAssertion(await authApi.assertionOptions()))
            await follow('DONE')
          })}
          onUseCode={() => setScreen({ name: 'recovery' })}
          onSignOut={signOut} />
      )}
      {screen.name === 'recovery' && (
        <RecoveryScreen texts={texts} busy={busy}
          onSubmit={(code) => run(async () => {
            const result = await authApi.recover(code)
            await follow('DONE', `${texts.replaceKey} ${fill(texts.remaining, { n: result.remainingRecoveryCodes })}`)
          })}
          onBack={() => setScreen({ name: 'verify' })} />
      )}
      {screen.name === 'codes' && (
        <RecoveryCodesScreen texts={texts} codes={screen.codes} onDone={() => run(() => follow('DONE'))} />
      )}
      {screen.name === 'home' && (
        <>
          <SessionScreen texts={texts} busy={busy} session={screen.session} notice={screen.notice} onSignOut={signOut}
            onAddKey={() => run(async () => {
              await registerKey(texts.addKey)
              await follow('DONE')
            })} />
          <Panels texts={texts} lang={lang} session={screen.session} run={run}
            onGrantUsed={async () => setScreen({ name: 'home', session: await authApi.session() })} />
        </>
      )}
    </Layout>
  )
}
