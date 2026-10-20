import { describe, expect, it } from 'vitest'
import { BackendApplicationAuthResponse } from '../../types/backendContracts'
import { AuthForm, EMPTY_AUTH_FORM, authFormToRequest, validateAuthForm } from './ApplicationAuthFields'

// Les messages attendus ici sont ceux de ApplicationAuthSupport (backend) :
// si l'un change d'un côté, ce test et ApplicationAuthSupportTest doivent
// changer ensemble.

const form = (patch: Partial<AuthForm>): AuthForm => ({ ...EMPTY_AUTH_FORM, ...patch })

const stored = (patch: Partial<BackendApplicationAuthResponse>): BackendApplicationAuthResponse => ({
  type: 'NONE', apiKeyName: null, apiKeyLocation: null, username: null, loginPath: null, tokenJsonPath: null,
  tokenHeaderName: null, tokenPrefix: null, verificationPath: null, tokenConfigured: false, apiKeyValueConfigured: false,
  passwordConfigured: false, loginBodyConfigured: false, ...patch,
})

describe('validateAuthForm — parité avec le backend', () => {
  it('NONE ne demande rien', () => {
    expect(validateAuthForm(form({ type: 'NONE' }))).toEqual({})
  })

  it('BEARER : jeton obligatoire, sans retour à la ligne', () => {
    expect(validateAuthForm(form({ type: 'BEARER_TOKEN', token: ' ', verificationPath: '/me' })).token).toBe('Le jeton est obligatoire.')
    expect(validateAuthForm(form({ type: 'BEARER_TOKEN', token: 'a\r\nX: 1' })).token).toBe('Le jeton ne doit pas contenir de retour à la ligne.')
  })

  it('API_KEY : nom d’en-tête ou de paramètre valide', () => {
    expect(validateAuthForm(form({ type: 'API_KEY', apiKeyName: 'X API', apiKeyValue: 'k' })).apiKeyName)
      .toBe("Nom d'en-tête invalide (lettres, chiffres et - uniquement).")
    expect(validateAuthForm(form({ type: 'API_KEY', apiKeyLocation: 'QUERY', apiKeyName: 'api key', apiKeyValue: 'k' })).apiKeyName)
      .toBe("Nom de paramètre invalide (pas d'espace ni de & = ? #).")
    expect(validateAuthForm(form({ type: 'API_KEY', apiKeyLocation: 'QUERY', apiKeyName: 'api_key', apiKeyValue: 'k', verificationPath: '/me' }))).toEqual({})
  })

  it('BASIC : pas de « : » dans l’utilisateur', () => {
    expect(validateAuthForm(form({ type: 'BASIC', username: 'a:b', password: 'p' })).username)
      .toBe("Le nom d'utilisateur ne doit pas contenir « : ».")
  })

  it('LOGIN_TOKEN : corps JSON, chemin de jeton supporté', () => {
    const base = { type: 'LOGIN_TOKEN' as const, loginPath: '/api/auth/login', loginBody: '{"u":"a"}', tokenJsonPath: '$.data.accessToken' }
    expect(validateAuthForm(form(base))).toEqual({})
    expect(validateAuthForm(form({ ...base, loginBody: '{u: a}' })).loginBody).toBe('Le corps doit être un JSON valide.')
    // Index de tableau acceptés (même sous-ensemble que JsonPathExtractor) ; index négatif ou non entier refusé.
    expect(validateAuthForm(form({ ...base, tokenJsonPath: '$.items[0].token' }))).toEqual({})
    expect(validateAuthForm(form({ ...base, tokenJsonPath: '$[0].data[1][2].token' }))).toEqual({})
    expect(validateAuthForm(form({ ...base, tokenJsonPath: '$.items[-1].token' })).tokenJsonPath)
      .toBe('Chemin invalide (ex : $.token ou $.data.accessToken).')
    expect(validateAuthForm(form({ ...base, tokenJsonPath: '$.items[a].token' })).tokenJsonPath)
      .toBe('Chemin invalide (ex : $.token ou $.data.accessToken).')
    // Jeton lu dans un en-tête de réponse : nom d'en-tête (ou Set-Cookie:NOM), messages identiques au serveur.
    const header = { ...base, tokenSource: 'HEADER' as const }
    expect(validateAuthForm(form({ ...header, tokenJsonPath: 'X-Auth-Token' }))).toEqual({})
    expect(validateAuthForm(form({ ...header, tokenJsonPath: 'Set-Cookie:ACCESS' }))).toEqual({})
    expect(validateAuthForm(form({ ...header, tokenJsonPath: '' })).tokenJsonPath).toBe("Indiquez l'en-tête qui contient le jeton (ex : X-Auth-Token).")
    expect(validateAuthForm(form({ ...header, tokenJsonPath: 'X Auth Token' })).tokenJsonPath)
      .toBe("Jeton : nom d'en-tête invalide (pas d'espace ni de caractère spécial, ex. X-CSRF-Token).")
    expect(authFormToRequest(form({ ...header, tokenJsonPath: 'X-Auth-Token' }))).toMatchObject({ tokenSource: 'HEADER', tokenJsonPath: 'X-Auth-Token' })
    expect('tokenSource' in authFormToRequest(form(base))).toBe(false)
    expect(validateAuthForm(form({ ...base, loginPath: '/api auth' })).loginPath).toBe("Le chemin ne doit pas contenir d'espace.")
    expect(validateAuthForm(form({ ...base, loginPath: 'https://sso.example.com/token' }))).toEqual({})
  })

  it('longueurs max = @Size du DTO', () => {
    expect(validateAuthForm(form({ type: 'BEARER_TOKEN', token: 'x'.repeat(4001) })).token)
      .toBe('Le jeton ne doit pas depasser 4000 caracteres.')
  })

  it('modification : secret vide accepté s’il est déjà enregistré pour le même type', () => {
    const existing = stored({ type: 'BEARER_TOKEN', tokenConfigured: true, verificationPath: '/me' })
    expect(validateAuthForm(form({ type: 'BEARER_TOKEN', token: '', verificationPath: '/me' }), existing)).toEqual({})
    // Changement de type : les anciens secrets ne comptent plus.
    expect(validateAuthForm(form({ type: 'BASIC', username: 'u', password: '' }), existing).password)
      .toBe('Le mot de passe est obligatoire.')
  })

  it('n’envoie que les champs du type choisi, secret vide = null (conservé)', () => {
    expect(authFormToRequest(form({ type: 'BEARER_TOKEN', token: '', username: 'ignored', verificationPath: ' /me ' })))
      .toEqual({ type: 'BEARER_TOKEN', token: null, verificationPath: '/me' })
  })

  it('Bearer / clé API / Basic : endpoint de vérification obligatoire, sans espace', () => {
    for (const patch of [
      { type: 'BEARER_TOKEN' as const, token: 't' },
      { type: 'API_KEY' as const, apiKeyName: 'X-API-Key', apiKeyValue: 'k' },
      { type: 'BASIC' as const, username: 'u', password: 'p' },
    ]) {
      expect(validateAuthForm(form({ ...patch, verificationPath: ' ' })).verificationPath)
        .toBe("Indiquez l'endpoint de vérification (ex : /api/account/balance).")
    }
    expect(validateAuthForm(form({ type: 'BASIC', username: 'u', password: 'p', verificationPath: '/api/account balance' })).verificationPath)
      .toBe("L'endpoint de vérification ne doit pas contenir d'espace.")
    expect(validateAuthForm(form({ type: 'BASIC', username: 'u', password: 'p', verificationPath: 'https://api.example.com/me' }))).toEqual({})
  })

  it('Aucune et Connexion auto : pas d’endpoint de vérification demandé', () => {
    expect(validateAuthForm(form({ type: 'NONE' })).verificationPath).toBeUndefined()
    const login = form({ type: 'LOGIN_TOKEN', loginBody: '{"u":"a"}' })
    expect(validateAuthForm(login)).toEqual({})
    expect(authFormToRequest(login)).not.toHaveProperty('verificationPath')
  })
})
