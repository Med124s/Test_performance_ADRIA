// ============================================================
// Politique de mot de passe du realm (GET /api/users/password-policy) :
// vérification en direct dans le formulaire et génération d'un mot de passe
// fort. Le serveur d'authentification reste seul juge : il re-vérifie chaque
// mot de passe à l'enregistrement (avec des messages en français).
// Mêmes règles que lui : « spécial » = tout caractère hors lettres,
// chiffres et « _ » ; nom d'utilisateur / e-mail comparés sans la casse.
// ============================================================

import type { BackendPasswordPolicy } from '../types/backendContracts'

export interface PasswordRule {
  id: string
  label: string
  ok: boolean
}

const count = (s: string, re: RegExp) => (s.match(re) ?? []).length
const plural = (n: number, one: string, many: string) => `${n} ${n > 1 ? many : one}`

/** Règles de la politique appliquées au mot de passe saisi, dans l'ordre d'affichage. */
export function checkPassword(password: string, policy: BackendPasswordPolicy | null,
  identity: { username?: string; email?: string } = {}): PasswordRule[] {
  const p = policy
  const rules: PasswordRule[] = []
  if (!p) return [{ id: 'required', label: 'Saisissez un mot de passe', ok: password.length > 0 }]
  const lower = password.toLowerCase()
  if (p.minLength > 0) rules.push({ id: 'length', label: `Au moins ${plural(p.minLength, 'caractère', 'caractères')}`, ok: password.length >= p.minLength })
  if (p.minUpperCase > 0) rules.push({ id: 'upper', label: `Au moins ${plural(p.minUpperCase, 'majuscule', 'majuscules')}`, ok: count(password, /\p{Lu}/gu) >= p.minUpperCase })
  if (p.minLowerCase > 0) rules.push({ id: 'lower', label: `Au moins ${plural(p.minLowerCase, 'minuscule', 'minuscules')}`, ok: count(password, /\p{Ll}/gu) >= p.minLowerCase })
  if (p.minDigits > 0) rules.push({ id: 'digits', label: `Au moins ${plural(p.minDigits, 'chiffre', 'chiffres')}`, ok: count(password, /\d/g) >= p.minDigits })
  if (p.minSpecialChars > 0) rules.push({ id: 'special', label: `Au moins ${plural(p.minSpecialChars, 'caractère spécial', 'caractères spéciaux')} (ex. ! ? # -)`, ok: count(password, /\W/g) >= p.minSpecialChars })
  if (p.maxLength > 0) rules.push({ id: 'max', label: `Au plus ${plural(p.maxLength, 'caractère', 'caractères')}`, ok: password.length <= p.maxLength })
  if (p.notUsername) rules.push({ id: 'username', label: 'Différent du nom d’utilisateur', ok: !identity.username?.trim() || lower !== identity.username.trim().toLowerCase() })
  if (p.notEmail) rules.push({ id: 'email', label: 'Différent de l’adresse e-mail', ok: !identity.email?.trim() || lower !== identity.email.trim().toLowerCase() })
  if (rules.length === 0) rules.push({ id: 'required', label: 'Saisissez un mot de passe', ok: password.length > 0 })
  return rules
}

// Caractères sans ambiguïté de lecture (pas de l / 1 / I / O / 0).
const UPPER = 'ABCDEFGHJKLMNPQRSTUVWXYZ'
const LOWER = 'abcdefghijkmnopqrstuvwxyz'
const DIGITS = '23456789'
const SPECIAL = '!#$%&*+-=?@'

function pick(alphabet: string): string {
  const n = new Uint32Array(1)
  crypto.getRandomValues(n)
  return alphabet[n[0] % alphabet.length]
}

/** Mot de passe fort et aléatoire (générateur cryptographique du navigateur), conforme à la politique. */
export function generatePassword(policy: BackendPasswordPolicy | null): string {
  const p = policy
  let length = Math.max(16, p?.minLength ?? 0)
  if (p && p.maxLength > 0) length = Math.min(length, p.maxLength)
  const chars = [
    ...Array.from({ length: Math.max(2, p?.minUpperCase ?? 0) }, () => pick(UPPER)),
    ...Array.from({ length: Math.max(2, p?.minLowerCase ?? 0) }, () => pick(LOWER)),
    ...Array.from({ length: Math.max(2, p?.minDigits ?? 0) }, () => pick(DIGITS)),
    ...Array.from({ length: Math.max(2, p?.minSpecialChars ?? 0) }, () => pick(SPECIAL)),
  ]
  const all = UPPER + LOWER + DIGITS + SPECIAL
  while (chars.length < length) chars.push(pick(all))
  // Mélange de Fisher-Yates.
  for (let i = chars.length - 1; i > 0; i--) {
    const r = new Uint32Array(1)
    crypto.getRandomValues(r)
    const j = r[0] % (i + 1)
    ;[chars[i], chars[j]] = [chars[j], chars[i]]
  }
  return chars.join('')
}
