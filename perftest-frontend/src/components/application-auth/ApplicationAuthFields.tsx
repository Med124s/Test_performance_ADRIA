import {
  BackendApiKeyLocation,
  BackendApplicationAuthRequest,
  BackendApplicationAuthResponse,
  BackendApplicationAuthType,
  BackendCaptureSource,
} from '../../types/backendContracts'
import { headerCaptureProblem, isSetCookie, joinHeaderCapture, splitHeaderCapture } from '../../utils/headerCapture'
import InfoTip from '../InfoTip'
import './applicationAuth.css'

// ============================================================
// Authentification de l'Application cible — formulaire, validation et
// conversion vers BackendApplicationAuthRequest. Les secrets ne sont jamais
// renvoyés par le backend : en modification, un champ secret laissé vide
// conserve la valeur enregistrée (indiqué dans le placeholder).
// ============================================================

export interface AuthForm {
  type: BackendApplicationAuthType
  token: string
  apiKeyName: string
  apiKeyValue: string
  apiKeyLocation: BackendApiKeyLocation
  username: string
  password: string
  loginPath: string
  loginBody: string
  tokenJsonPath: string
  /** Où lire le jeton : corps JSON (tokenJsonPath = chemin) ou en-tête de réponse (tokenJsonPath = nom de l'en-tête). */
  tokenSource: BackendCaptureSource
  tokenHeaderName: string
  tokenPrefix: string
  verificationPath: string
}

export const EMPTY_AUTH_FORM: AuthForm = {
  type: 'NONE',
  token: '',
  apiKeyName: 'X-API-Key',
  apiKeyValue: '',
  apiKeyLocation: 'HEADER',
  username: '',
  password: '',
  loginPath: '/api/auth/login',
  loginBody: '{\n  "username": "",\n  "password": ""\n}',
  tokenJsonPath: '$.token',
  tokenSource: 'JSON',
  tokenHeaderName: 'Authorization',
  tokenPrefix: 'Bearer ',
  verificationPath: '',
}

/** Formulaire pré-rempli depuis la vue backend (sans secret). */
export function authFormFromResponse(auth: BackendApplicationAuthResponse | null | undefined): AuthForm {
  if (!auth || auth.type === 'NONE') return { ...EMPTY_AUTH_FORM }
  return {
    ...EMPTY_AUTH_FORM,
    type: auth.type,
    apiKeyName: auth.apiKeyName ?? EMPTY_AUTH_FORM.apiKeyName,
    apiKeyLocation: auth.apiKeyLocation ?? 'HEADER',
    username: auth.username ?? '',
    loginPath: auth.loginPath ?? EMPTY_AUTH_FORM.loginPath,
    loginBody: '',
    tokenJsonPath: auth.tokenJsonPath ?? EMPTY_AUTH_FORM.tokenJsonPath,
    tokenSource: auth.tokenSource === 'HEADER' ? 'HEADER' : 'JSON',
    tokenHeaderName: auth.tokenHeaderName ?? EMPTY_AUTH_FORM.tokenHeaderName,
    tokenPrefix: auth.tokenPrefix ?? EMPTY_AUTH_FORM.tokenPrefix,
    verificationPath: auth.verificationPath ?? '',
  }
}

// Mêmes motifs que ApplicationAuthSupport (backend).
const HEADER_NAME = /^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/
const QUERY_NAME = /^[^\s&=?#]+$/
const JSON_PATH = /^(\$\.?)?(?:[A-Za-z0-9_-]+|\[\d{1,9}])(?:\[\d{1,9}])*(\.[A-Za-z0-9_-]+(?:\[\d{1,9}])*)*$/
const LINE_BREAK = /[\r\n\u0000]/

/** Longueurs maximales = @Size de ApplicationAuthRequest (messages identiques). */
const MAX_LENGTH: Partial<Record<keyof AuthForm, [number, string]>> = {
  token: [4000, 'Le jeton ne doit pas depasser 4000 caracteres.'],
  apiKeyName: [255, 'Le nom de la cle API ne doit pas depasser 255 caracteres.'],
  apiKeyValue: [4000, 'La cle API ne doit pas depasser 4000 caracteres.'],
  username: [255, "L'utilisateur ne doit pas depasser 255 caracteres."],
  password: [1000, 'Le mot de passe ne doit pas depasser 1000 caracteres.'],
  loginPath: [1000, 'Le chemin de connexion ne doit pas depasser 1000 caracteres.'],
  loginBody: [4000, 'Le corps de connexion ne doit pas depasser 4000 caracteres.'],
  tokenJsonPath: [500, 'Le chemin du jeton ne doit pas depasser 500 caracteres.'],
  tokenHeaderName: [255, "Le nom d'en-tete ne doit pas depasser 255 caracteres."],
  tokenPrefix: [50, 'Le prefixe du jeton ne doit pas depasser 50 caracteres.'],
  verificationPath: [1000, "L'endpoint de verification ne doit pas depasser 1000 caracteres."],
}

/** Types dont le test de connectivité vérifie les identifiants sur un
 * endpoint protégé (même liste que ApplicationServiceImpl). */
export const CREDENTIAL_CHECK_TYPES: BackendApplicationAuthType[] = ['BEARER_TOKEN', 'API_KEY', 'BASIC']

/** Champs réellement envoyés pour chaque type (voir authFormToRequest). */
const FIELDS_BY_TYPE: Record<BackendApplicationAuthType, (keyof AuthForm)[]> = {
  NONE: [],
  BEARER_TOKEN: ['token', 'verificationPath'],
  API_KEY: ['apiKeyName', 'apiKeyValue', 'verificationPath'],
  BASIC: ['username', 'password', 'verificationPath'],
  LOGIN_TOKEN: ['loginPath', 'loginBody', 'tokenJsonPath', 'tokenHeaderName', 'tokenPrefix'],
}

/** Secret déjà enregistré pour CE type (champ vide = conservé). */
function storedSecret(existing: BackendApplicationAuthResponse | null | undefined, form: AuthForm, field: 'token' | 'apiKeyValue' | 'password' | 'loginBody'): boolean {
  if (!existing || existing.type !== form.type) return false
  return {
    token: existing.tokenConfigured,
    apiKeyValue: existing.apiKeyValueConfigured,
    password: existing.passwordConfigured,
    loginBody: existing.loginBodyConfigured,
  }[field]
}

/**
 * Règles et messages IDENTIQUES à ApplicationAuthSupport#apply (backend) —
 * toute modification doit être faite des deux côtés (verrouillé par
 * ApplicationAuthFields.test.ts et ApplicationAuthSupportTest).
 */
export function validateAuthForm(form: AuthForm, existing?: BackendApplicationAuthResponse | null): Partial<Record<keyof AuthForm, string>> {
  const errors: Partial<Record<keyof AuthForm, string>> = {}
  const missing = (field: 'token' | 'apiKeyValue' | 'password' | 'loginBody') =>
    !form[field].trim() && !storedSecret(existing, form, field)
  const set = (field: keyof AuthForm, message: string) => {
    if (!errors[field]) errors[field] = message
  }

  for (const field of FIELDS_BY_TYPE[form.type]) {
    const limit = MAX_LENGTH[field]
    if (limit && form[field].length > limit[0]) set(field, limit[1])
  }

  switch (form.type) {
    case 'BEARER_TOKEN':
      if (missing('token')) set('token', 'Le jeton est obligatoire.')
      else if (LINE_BREAK.test(form.token)) set('token', 'Le jeton ne doit pas contenir de retour à la ligne.')
      break
    case 'API_KEY': {
      const name = form.apiKeyName.trim()
      if (!name) set('apiKeyName', 'Indiquez le nom de la clé (ex : X-API-Key).')
      else if (form.apiKeyLocation === 'HEADER' && !HEADER_NAME.test(name)) set('apiKeyName', "Nom d'en-tête invalide (lettres, chiffres et - uniquement).")
      else if (form.apiKeyLocation === 'QUERY' && !QUERY_NAME.test(name)) set('apiKeyName', "Nom de paramètre invalide (pas d'espace ni de & = ? #).")
      if (missing('apiKeyValue')) set('apiKeyValue', 'La valeur de la clé est obligatoire.')
      else if (LINE_BREAK.test(form.apiKeyValue)) set('apiKeyValue', 'La clé ne doit pas contenir de retour à la ligne.')
      break
    }
    case 'BASIC': {
      const username = form.username.trim()
      if (!username) set('username', "Le nom d'utilisateur est obligatoire.")
      else if (username.includes(':')) set('username', "Le nom d'utilisateur ne doit pas contenir « : ».")
      else if (LINE_BREAK.test(username)) set('username', "Le nom d'utilisateur ne doit pas contenir de retour à la ligne.")
      if (missing('password')) set('password', 'Le mot de passe est obligatoire.')
      break
    }
    case 'LOGIN_TOKEN': {
      const loginPath = form.loginPath.trim()
      if (!loginPath) set('loginPath', "Indiquez l'endpoint de connexion (ex : /api/auth/login).")
      else if (/\s/.test(loginPath)) set('loginPath', "Le chemin ne doit pas contenir d'espace.")
      if (form.loginBody.trim()) {
        try { JSON.parse(form.loginBody) } catch { set('loginBody', 'Le corps doit être un JSON valide.') }
      } else if (missing('loginBody')) {
        set('loginBody', 'Indiquez les identifiants envoyés (JSON).')
      }
      const tokenJsonPath = form.tokenJsonPath.trim()
      if (form.tokenSource === 'HEADER') {
        // tokenJsonPath = nom de l'en-tête de réponse qui contient le jeton (ou « Set-Cookie:NOM »).
        const problem = tokenJsonPath ? headerCaptureProblem(tokenJsonPath) : null
        if (!tokenJsonPath) set('tokenJsonPath', "Indiquez l'en-tête qui contient le jeton (ex : X-Auth-Token).")
        else if (problem) set('tokenJsonPath', `Jeton : ${problem}.`)
      } else if (!tokenJsonPath) set('tokenJsonPath', 'Indiquez où lire le jeton (ex : $.token).')
      else if (!JSON_PATH.test(tokenJsonPath)) set('tokenJsonPath', 'Chemin invalide (ex : $.token ou $.data.accessToken).')
      const headerName = form.tokenHeaderName.trim()
      if (headerName && !HEADER_NAME.test(headerName)) set('tokenHeaderName', "Nom d'en-tête invalide (lettres, chiffres et - uniquement).")
      if (LINE_BREAK.test(form.tokenPrefix)) set('tokenPrefix', 'Le préfixe ne doit pas contenir de retour à la ligne.')
      break
    }
  }
  if (CREDENTIAL_CHECK_TYPES.includes(form.type)) {
    const verificationPath = form.verificationPath.trim()
    if (!verificationPath) set('verificationPath', "Indiquez l'endpoint de vérification (ex : /api/account/balance).")
    else if (/\s/.test(verificationPath)) set('verificationPath', "L'endpoint de vérification ne doit pas contenir d'espace.")
  }
  return errors
}

export function authFormToRequest(form: AuthForm): BackendApplicationAuthRequest {
  const opt = (v: string) => (v.trim() ? v.trim() : null)
  switch (form.type) {
    case 'BEARER_TOKEN':
      return { type: form.type, token: opt(form.token), verificationPath: opt(form.verificationPath) }
    case 'API_KEY':
      return { type: form.type, apiKeyName: opt(form.apiKeyName), apiKeyValue: opt(form.apiKeyValue), apiKeyLocation: form.apiKeyLocation, verificationPath: opt(form.verificationPath) }
    case 'BASIC':
      return { type: form.type, username: opt(form.username), password: form.password ? form.password : null, verificationPath: opt(form.verificationPath) }
    case 'LOGIN_TOKEN':
      return {
        type: form.type,
        loginPath: opt(form.loginPath),
        loginBody: form.loginBody.trim() ? form.loginBody : null,
        tokenJsonPath: opt(form.tokenJsonPath),
        // Absent = JSON (comportement historique).
        ...(form.tokenSource === 'HEADER' ? { tokenSource: 'HEADER' as const } : {}),
        tokenHeaderName: opt(form.tokenHeaderName),
        // Préfixe envoyé tel quel (y compris vide = jeton brut).
        tokenPrefix: form.tokenPrefix,
      }
    default:
      return { type: 'NONE' }
  }
}

export const AUTH_TYPES: { type: BackendApplicationAuthType; label: string; icon: string; hint: string }[] = [
  { type: 'NONE', label: 'Aucune', icon: 'bi-unlock', hint: 'Application publique, aucune authentification.' },
  { type: 'LOGIN_TOKEN', label: 'Connexion auto', icon: 'bi-box-arrow-in-right', hint: 'LoadPilot se connecte, récupère le jeton et l’envoie à chaque requête.' },
  { type: 'BEARER_TOKEN', label: 'Jeton Bearer', icon: 'bi-key', hint: 'Un jeton fixe envoyé dans « Authorization: Bearer … ».' },
  { type: 'API_KEY', label: 'Clé API', icon: 'bi-shield-lock', hint: 'Une clé envoyée dans un en-tête ou un paramètre d’URL.' },
  { type: 'BASIC', label: 'Basic', icon: 'bi-person-lock', hint: 'Utilisateur et mot de passe (Authorization: Basic …).' },
]

export function authTypeLabel(type: BackendApplicationAuthType | undefined): string {
  return AUTH_TYPES.find((t) => t.type === type)?.label ?? 'Aucune'
}

interface ApplicationAuthFieldsProps {
  value: AuthForm
  onChange: (next: AuthForm) => void
  errors: Partial<Record<keyof AuthForm, string>>
  showErrors: boolean
  /** Configuration enregistrée (modification) : secrets conservés si vides. */
  existing?: BackendApplicationAuthResponse | null
  /** URL de l'application, pour montrer l'appel de login réellement envoyé. */
  baseUrl?: string
}

/** Même résolution que UrlResolver (backend) : URL absolue gardée telle quelle,
 * sinon ajoutée à l'URL de l'application. */
function resolveLoginUrl(baseUrl: string, path: string): string {
  if (/^https?:\/\//i.test(path)) return path
  return baseUrl.replace(/\/+$/, '') + (path.startsWith('/') ? path : `/${path}`)
}

function ApplicationAuthFields({ value, onChange, errors, showErrors, existing, baseUrl }: ApplicationAuthFieldsProps) {
  const set = (patch: Partial<AuthForm>) => onChange({ ...value, ...patch })
  const err = (field: keyof AuthForm) => (showErrors ? errors[field] : undefined)
  const keptPlaceholder = (field: 'token' | 'apiKeyValue' | 'password' | 'loginBody', fallback: string) =>
    storedSecret(existing, value, field) ? '•••••• enregistré — laisser vide pour conserver' : fallback
  const current = AUTH_TYPES.find((t) => t.type === value.type) ?? AUTH_TYPES[0]

  const preview = (() => {
    switch (value.type) {
      case 'BEARER_TOKEN': return 'Authorization: Bearer ••••••'
      case 'API_KEY': return value.apiKeyLocation === 'QUERY'
        ? `?${value.apiKeyName.trim() || 'api_key'}=••••••`
        : `${value.apiKeyName.trim() || 'X-API-Key'}: ••••••`
      case 'BASIC': return 'Authorization: Basic base64(utilisateur:mot de passe)'
      case 'LOGIN_TOKEN': return `${value.tokenHeaderName.trim() || 'Authorization'}: ${value.tokenPrefix}<jeton lu dans ${value.tokenSource === 'HEADER'
        ? `l’en-tête ${value.tokenJsonPath.trim() || '…'}` : value.tokenJsonPath.trim() || '$.token'}>`
      default: return null
    }
  })()

  return (
    <div className="aa-root">
      <div className="aa-types" role="radiogroup" aria-label="Type d’authentification">
        {AUTH_TYPES.map((t) => (
          <button
            key={t.type}
            type="button"
            role="radio"
            aria-checked={value.type === t.type}
            className={`aa-type ${value.type === t.type ? 'active' : ''}`}
            onClick={() => set({ type: t.type })}
          >
            <i className={`bi ${t.icon}`}></i>
            <span>{t.label}</span>
          </button>
        ))}
      </div>
      <div className="aa-hint">{current.hint}</div>

      {value.type === 'BEARER_TOKEN' && (
        <div className="aa-fields">
          <label className="aa-label">Jeton *</label>
          <input className={`pt-form-control aa-mono ${err('token') ? 'is-invalid' : ''}`} type="password" autoComplete="off"
            placeholder={keptPlaceholder('token', 'ex : eyJhbGciOi… ou TEST-TOKEN-123')} value={value.token} onChange={(e) => set({ token: e.target.value })} />
          {err('token') && <div className="aa-error">{err('token')}</div>}
        </div>
      )}

      {value.type === 'API_KEY' && (
        <div className="aa-fields">
          <div className="aa-row">
            <div>
              <label className="aa-label">Envoyer la clé dans</label>
              <div className="aa-segmented">
                {(['HEADER', 'QUERY'] as BackendApiKeyLocation[]).map((loc) => (
                  <button key={loc} type="button" className={value.apiKeyLocation === loc ? 'active' : ''} onClick={() => set({ apiKeyLocation: loc, apiKeyName: loc === 'QUERY' && value.apiKeyName === 'X-API-Key' ? 'api_key' : loc === 'HEADER' && value.apiKeyName === 'api_key' ? 'X-API-Key' : value.apiKeyName })}>
                    {loc === 'HEADER' ? 'En-tête' : 'Paramètre d’URL'}
                  </button>
                ))}
              </div>
            </div>
            <div className="aa-grow">
              <label className="aa-label">Nom *</label>
              <input className={`pt-form-control aa-mono ${err('apiKeyName') ? 'is-invalid' : ''}`} value={value.apiKeyName} onChange={(e) => set({ apiKeyName: e.target.value })} />
              {err('apiKeyName') && <div className="aa-error">{err('apiKeyName')}</div>}
            </div>
          </div>
          <label className="aa-label">Valeur de la clé *</label>
          <input className={`pt-form-control aa-mono ${err('apiKeyValue') ? 'is-invalid' : ''}`} type="password" autoComplete="off"
            placeholder={keptPlaceholder('apiKeyValue', 'ex : DEMO-API-KEY-2026')} value={value.apiKeyValue} onChange={(e) => set({ apiKeyValue: e.target.value })} />
          {err('apiKeyValue') && <div className="aa-error">{err('apiKeyValue')}</div>}
        </div>
      )}

      {value.type === 'BASIC' && (
        <div className="aa-fields">
          <div className="aa-row">
            <div className="aa-grow">
              <label className="aa-label">Utilisateur *</label>
              <input className={`pt-form-control ${err('username') ? 'is-invalid' : ''}`} autoComplete="off" value={value.username} onChange={(e) => set({ username: e.target.value })} placeholder="ex : agent001" />
              {err('username') && <div className="aa-error">{err('username')}</div>}
            </div>
            <div className="aa-grow">
              <label className="aa-label">Mot de passe *</label>
              <input className={`pt-form-control ${err('password') ? 'is-invalid' : ''}`} type="password" autoComplete="new-password"
                placeholder={keptPlaceholder('password', '••••••')} value={value.password} onChange={(e) => set({ password: e.target.value })} />
              {err('password') && <div className="aa-error">{err('password')}</div>}
            </div>
          </div>
        </div>
      )}

      {value.type === 'LOGIN_TOKEN' && (
        <div className="aa-fields">
          <label className="aa-label">Endpoint de connexion (POST) *</label>
          <div className="aa-inline">
            <span className="aa-method">POST</span>
            <input className={`pt-form-control aa-mono ${err('loginPath') ? 'is-invalid' : ''}`} value={value.loginPath} onChange={(e) => set({ loginPath: e.target.value })} placeholder="/api/auth/login" />
          </div>
          {err('loginPath') ? (
            <div className="aa-error">{err('loginPath')}</div>
          ) : baseUrl?.trim() && value.loginPath.trim() ? (
            <div className="aa-help">Appel réel : <code>POST {resolveLoginUrl(baseUrl.trim(), value.loginPath.trim())}</code></div>
          ) : null}

          <label className="aa-label">Identifiants envoyés (JSON) *</label>
          <textarea className={`pt-form-control aa-mono ${err('loginBody') ? 'is-invalid' : ''}`} rows={4} spellCheck={false}
            placeholder={keptPlaceholder('loginBody', '{"username": "…", "password": "…"}')} value={value.loginBody} onChange={(e) => set({ loginBody: e.target.value })} />
          {err('loginBody') && <div className="aa-error">{err('loginBody')}</div>}

          <label className="aa-label">
            Lire le jeton dans
            <InfoTip text="Le jeton est aussi disponible dans vos étapes via ${authToken}." />
          </label>
          <div className="aa-segmented mb-2">
            {(['JSON', 'HEADER'] as BackendCaptureSource[]).map((source) => (
              <button key={source} type="button" className={value.tokenSource === source ? 'active' : ''}
                onClick={() => set({ tokenSource: source, tokenJsonPath: source === value.tokenSource ? value.tokenJsonPath : source === 'JSON' ? '$.token' : '' })}>
                {source === 'JSON' ? 'Corps JSON' : 'En-tête de réponse'}
              </button>
            ))}
          </div>
          <div className="aa-row">
            {value.tokenSource === 'HEADER' ? (
              <div className="aa-grow">
                <label className="aa-label">En-tête de réponse *</label>
                <input className={`pt-form-control aa-mono ${err('tokenJsonPath') ? 'is-invalid' : ''}`} value={splitHeaderCapture(value.tokenJsonPath).header}
                  onChange={(e) => set({ tokenJsonPath: joinHeaderCapture(e.target.value, splitHeaderCapture(value.tokenJsonPath).cookie) })}
                  placeholder="X-Auth-Token" aria-label="En-tête de réponse qui contient le jeton" />
                {isSetCookie(splitHeaderCapture(value.tokenJsonPath).header) && (
                  <input className="pt-form-control aa-mono mt-1" value={splitHeaderCapture(value.tokenJsonPath).cookie}
                    onChange={(e) => set({ tokenJsonPath: joinHeaderCapture(splitHeaderCapture(value.tokenJsonPath).header, e.target.value) })}
                    placeholder="Nom du cookie (ex : SESSIONID)" aria-label="Nom du cookie qui contient le jeton" />
                )}
                {err('tokenJsonPath') && <div className="aa-error">{err('tokenJsonPath')}</div>}
              </div>
            ) : (
              <div className="aa-grow">
                <label className="aa-label">Chemin du token *</label>
                <input className={`pt-form-control aa-mono ${err('tokenJsonPath') ? 'is-invalid' : ''}`} value={value.tokenJsonPath} onChange={(e) => set({ tokenJsonPath: e.target.value })} placeholder="$.token" />
                {err('tokenJsonPath') && <div className="aa-error">{err('tokenJsonPath')}</div>}
              </div>
            )}
            <div className="aa-grow">
              <label className="aa-label">En-tête</label>
              <input className={`pt-form-control aa-mono ${err('tokenHeaderName') ? 'is-invalid' : ''}`} value={value.tokenHeaderName} onChange={(e) => set({ tokenHeaderName: e.target.value })} placeholder="Authorization" />
              {err('tokenHeaderName') && <div className="aa-error">{err('tokenHeaderName')}</div>}
            </div>
            <div style={{ width: '110px' }}>
              <label className="aa-label">Préfixe</label>
              <input className={`pt-form-control aa-mono ${err('tokenPrefix') ? 'is-invalid' : ''}`} value={value.tokenPrefix} onChange={(e) => set({ tokenPrefix: e.target.value })} placeholder="(aucun)" />
              {err('tokenPrefix') && <div className="aa-error">{err('tokenPrefix')}</div>}
            </div>
          </div>
        </div>
      )}

      {CREDENTIAL_CHECK_TYPES.includes(value.type) && (
        <div className="aa-fields">
          <label className="aa-label" htmlFor="aa-verification-path">
            Endpoint de vérification *
            <InfoTip text="Chemin protégé de l’application : il doit répondre 401 ou 403 sans identifiants. Le test l’appelle sans puis avec vos identifiants." />
          </label>
          <div className="aa-inline">
            <span className="aa-method">GET</span>
            <input
              id="aa-verification-path"
              className={`pt-form-control aa-mono ${err('verificationPath') ? 'is-invalid' : ''}`}
              value={value.verificationPath}
              onChange={(e) => set({ verificationPath: e.target.value })}
              placeholder="/api/auth/verify"
              spellCheck={false}
            />
          </div>
          {err('verificationPath') ? (
            <div className="aa-error">{err('verificationPath')}</div>
          ) : baseUrl?.trim() && value.verificationPath.trim() ? (
            <div className="aa-help">Appel réel : <code>GET {resolveLoginUrl(baseUrl.trim(), value.verificationPath.trim())}</code></div>
          ) : null}
        </div>
      )}

      {preview && (
        <div className="aa-preview">
          <i className="bi bi-arrow-return-right"></i>
          <span>Ajouté à chaque requête : <code>{preview}</code></span>
        </div>
      )}
    </div>
  )
}

export default ApplicationAuthFields
