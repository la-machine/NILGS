// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// WebAuthn in the browser (ADR-016). The server sends options as WebAuthn JSON
// (binary values in base64url) and expects the credential back in the same form; this
// file converts both ways, so it works in every browser that supports WebAuthn, with
// or without PublicKeyCredential.parseCreationOptionsFromJSON. The private key never
// leaves the authenticator; the user proves presence and identity (PIN or biometric).

type Json = Record<string, unknown>

export function webAuthnAvailable(): boolean {
  return typeof window.PublicKeyCredential === 'function' && !!navigator.credentials
}

function fromBase64url(value: string): ArrayBuffer {
  const base64 = value.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(value.length / 4) * 4, '=')
  const binary = atob(base64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return bytes.buffer
}

function toBase64url(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer)
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function descriptors(list: unknown): PublicKeyCredentialDescriptor[] | undefined {
  if (!Array.isArray(list)) return undefined
  return list.map((d: Json) => ({
    type: 'public-key',
    id: fromBase64url(d.id as string),
    transports: d.transports as AuthenticatorTransport[] | undefined,
  }))
}

function common(credential: PublicKeyCredential): Json {
  return {
    id: credential.id,
    rawId: toBase64url(credential.rawId),
    type: credential.type,
    clientExtensionResults: credential.getClientExtensionResults(),
    authenticatorAttachment: credential.authenticatorAttachment ?? undefined,
  }
}

/** Registers a new authenticator with the server's creation options. */
export async function createCredential(options: Json): Promise<Json> {
  const user = options.user as Json
  const publicKey: PublicKeyCredentialCreationOptions = {
    rp: options.rp as PublicKeyCredentialRpEntity,
    user: { id: fromBase64url(user.id as string), name: user.name as string, displayName: user.displayName as string },
    challenge: fromBase64url(options.challenge as string),
    pubKeyCredParams: options.pubKeyCredParams as PublicKeyCredentialParameters[],
    timeout: options.timeout as number | undefined,
    excludeCredentials: descriptors(options.excludeCredentials),
    authenticatorSelection: options.authenticatorSelection as AuthenticatorSelectionCriteria | undefined,
    attestation: options.attestation as AttestationConveyancePreference | undefined,
  }
  const credential = (await navigator.credentials.create({ publicKey })) as PublicKeyCredential | null
  if (!credential) throw new Error('NO_CREDENTIAL')
  const response = credential.response as AuthenticatorAttestationResponse
  return {
    ...common(credential),
    response: {
      clientDataJSON: toBase64url(response.clientDataJSON),
      attestationObject: toBase64url(response.attestationObject),
      transports: typeof response.getTransports === 'function' ? response.getTransports() : [],
    },
  }
}

/** Proves possession of one of the account's authenticators with the server's request options. */
export async function getAssertion(options: Json): Promise<Json> {
  const publicKey: PublicKeyCredentialRequestOptions = {
    challenge: fromBase64url(options.challenge as string),
    timeout: options.timeout as number | undefined,
    rpId: options.rpId as string | undefined,
    allowCredentials: descriptors(options.allowCredentials),
    userVerification: options.userVerification as UserVerificationRequirement | undefined,
  }
  const credential = (await navigator.credentials.get({ publicKey })) as PublicKeyCredential | null
  if (!credential) throw new Error('NO_CREDENTIAL')
  const response = credential.response as AuthenticatorAssertionResponse
  return {
    ...common(credential),
    response: {
      clientDataJSON: toBase64url(response.clientDataJSON),
      authenticatorData: toBase64url(response.authenticatorData),
      signature: toBase64url(response.signature),
      userHandle: response.userHandle ? toBase64url(response.userHandle) : undefined,
    },
  }
}
