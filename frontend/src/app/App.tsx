// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The application: the sign-in steps (the server decides the next one), then the signed-in
// shell with the page the address names (#/parcels/...). One place runs actions with a busy
// state and turns errors into messages. Pages live in their features; this file only wires.
import { useCallback, useEffect, useState } from 'react'
import {
  authApi, createCredential, EnrolScreen, getAssertion, LoginScreen, RecoveryCodesScreen, RecoveryScreen,
  RegisterScreen, VerifyScreen,
} from '../features/auth'
import { ApiError } from '../shared/api/client'
import { fill, t } from '../shared/i18n'
import type { Lang } from '../shared/i18n'
import { useRoute } from '../shared/router/router'
import type { NextStep } from '../shared/session/types'
import { DialogProvider } from '../shared/ui/Dialog'
import { Alert, Spinner } from '../shared/ui/Feedback'
import { errorMessage } from '../shared/ui/errorMessage'
import { AppShell } from './AppShell'
import { AuthLayout } from './AuthLayout'
import { placeOf } from './navigation'
import type { PlaceId } from './navigation'
import { Pages } from './Pages'
import type { Screen } from './screens'

const LANG_KEY = 'snigf.lang'

function initialLang(): Lang {
  try {
    return window.localStorage.getItem(LANG_KEY) === 'en' ? 'en' : 'fr'
  } catch {
    return 'fr'
  }
}

export function App() {
  const [lang, setLang] = useState<Lang>(initialLang)
  const [screen, setScreen] = useState<Screen>({ name: 'loading' })
  const [error, setError] = useState<string | null>(null)
  const [info, setInfo] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [auditSeen, setAuditSeen] = useState(false)
  const texts = t(lang)
  const route = useRoute()

  useEffect(() => {
    document.documentElement.lang = lang
    try {
      window.localStorage.setItem(LANG_KEY, lang)
    } catch {
      // Private browsing: the choice lasts for this page only.
    }
  }, [lang])

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

  const refreshSession = async () => {
    const session = await authApi.session()
    setScreen((s) => (s.name === 'home' ? { ...s, session } : s))
  }

  const toggleLang = () => setLang(lang === 'fr' ? 'en' : 'fr')

  // Moving to another place reads the session again, quietly: a role approved or revoked
  // meanwhile changes the menu and the pages without a reload. The server decides anyway.
  // Coming back to the window, or choosing the current place again, does the same.
  const place = route.segments[0] ?? 'dashboard'
  const signedIn = screen.name === 'home'
  const [refreshes, setRefreshes] = useState(0)
  useEffect(() => {
    if (!signedIn) return
    authApi.session().then((session) => {
      setScreen((s) => (s.name === 'home' ? { ...s, session } : s))
    }).catch(() => { /* the next protected call reports an expired session */ })
  }, [place, signedIn, refreshes])
  useEffect(() => {
    const onFocus = () => setRefreshes((n) => n + 1)
    window.addEventListener('focus', onFocus)
    return () => window.removeEventListener('focus', onFocus)
  }, [])

  if (screen.name === 'home') {
    const first = route.segments[0] ?? 'dashboard'
    const place = placeOf(first)
    const active = (place?.id ?? null) as PlaceId | null
    const auditVisible = active === 'audit' && (auditSeen || place?.visible(screen.session))
    if (active === 'audit' && place?.visible(screen.session) && !auditSeen) setAuditSeen(true)
    return (
      <DialogProvider>
        <AppShell texts={texts} lang={lang} session={screen.session} active={active} busy={busy} error={error}
          onDismissError={() => setError(null)} onToggleLang={toggleLang} onSignOut={signOut}
          extraVisible={auditSeen ? ['audit'] : []} onRefresh={() => setRefreshes((n) => n + 1)}>
          <Pages route={route} place={active} allowed={!!place && (place.visible(screen.session) || !!auditVisible)}
            ctx={{ texts, lang, session: screen.session, run, refreshSession, notice: screen.notice }}
            onAddKey={(label) => run(async () => {
              const credential = await createCredential(await authApi.registrationOptions())
              await authApi.registerKey(credential, label)
              await refreshSession()
            })} />
        </AppShell>
      </DialogProvider>
    )
  }

  const message = error
    ? <Alert tone="danger">{error}</Alert>
    : info ? <Alert tone="success">{info}</Alert> : null

  return (
    <AuthLayout texts={texts} lang={lang} onToggleLang={toggleLang} error={message}>
      {screen.name === 'loading' && (
        <div className="auth-card auth-loading"><Spinner label={texts.shell.loading} /></div>
      )}
      {screen.name === 'login' && (
        <LoginScreen texts={texts} busy={busy}
          onSubmit={(email, password) => run(async () => {
            setInfo(null)
            await follow((await authApi.login(email, password)).nextStep)
          })}
          onRegister={() => { setError(null); setInfo(null); setScreen({ name: 'register' }) }} />
      )}
      {screen.name === 'register' && (
        <RegisterScreen texts={texts} busy={busy} lang={lang}
          onSubmit={(body) => run(async () => {
            await authApi.register(body)
            setScreen({ name: 'login' })
            setInfo(texts.registered)
          })}
          onBack={() => { setError(null); setScreen({ name: 'login' }) }} />
      )}
      {screen.name === 'enrol' && (
        <EnrolScreen texts={texts} busy={busy} registered={screen.registered} required={screen.required}
          onRegister={(label) => run(async () => {
            const credential = await createCredential(await authApi.registrationOptions())
            const result = await authApi.registerKey(credential, label)
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
          onUseCode={() => { setError(null); setScreen({ name: 'recovery' }) }}
          onSignOut={signOut} />
      )}
      {screen.name === 'recovery' && (
        <RecoveryScreen texts={texts} busy={busy}
          onSubmit={(code) => run(async () => {
            const result = await authApi.recover(code)
            await follow('DONE', `${texts.replaceKey} ${fill(texts.remaining, { n: result.remainingRecoveryCodes })} ${texts.removeLostKeys}`)
            window.location.hash = '#/account'
          })}
          onBack={() => setScreen({ name: 'verify' })} />
      )}
      {screen.name === 'codes' && (
        <RecoveryCodesScreen texts={texts} codes={screen.codes} onDone={() => run(() => follow('DONE'))} />
      )}
    </AuthLayout>
  )
}
