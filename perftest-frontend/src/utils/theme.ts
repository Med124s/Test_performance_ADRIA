import { useSyncExternalStore } from 'react'

// ============================================================
// Apparence : UN SEUL état pour toute l'application (bouton de la barre latérale,
// Paramètres > Mon profil), trois choix :
//   - « light » / « dark » : imposés ;
//   - « system » : suit le réglage de l'appareil (prefers-color-scheme), en direct.
// Mémorisée dans ce navigateur (affichage immédiat au chargement) ET sur le profil
// (retrouvée sur un autre appareil, voir applyProfileTheme). L'attribut `data-theme`
// de <html> reste la seule chose lue par le CSS.
// ============================================================

export type ThemePreference = 'light' | 'dark' | 'system'

const STORAGE_KEY = 'pt_theme'
/** Ancienne clé (interrupteur clair / sombre) : relue une fois pour ne rien perdre. */
const LEGACY_KEY = 'pt_dark_mode'

type Listener = () => void
const listeners = new Set<Listener>()
/** Enregistrement sur le profil (branché par l'application, voir setThemeSaver). */
let saver: ((preference: ThemePreference) => void) | null = null

function readStored(): ThemePreference {
  try {
    const value = localStorage.getItem(STORAGE_KEY)
    if (value === 'light' || value === 'dark' || value === 'system') return value
    const legacy = localStorage.getItem(LEGACY_KEY)
    if (legacy === '1') return 'dark'
    if (legacy === '0') return 'light'
  } catch {
    // stockage indisponible : valeur par défaut
  }
  return 'light'
}

function systemIsDark(): boolean {
  try {
    return typeof window !== 'undefined' && !!window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches
  } catch {
    return false
  }
}

let preference: ThemePreference = readStored()
let dark = resolve(preference)

function resolve(p: ThemePreference): boolean {
  return p === 'dark' || (p === 'system' && systemIsDark())
}

function apply() {
  dark = resolve(preference)
  if (typeof document !== 'undefined') document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light')
  listeners.forEach((l) => l())
}

apply()

// « Système » : suit les changements de l'appareil sans recharger la page.
try {
  const media = typeof window !== 'undefined' && window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null
  media?.addEventListener?.('change', () => { if (preference === 'system') apply() })
} catch {
  // navigateur ancien : « Système » est évalué au chargement seulement
}

export function getThemePreference(): ThemePreference {
  return preference
}

export function isDarkTheme(): boolean {
  return dark
}

/** Choix de l'utilisateur : appliqué partout, mémorisé ici et sur le profil (save = false : pas d'enregistrement). */
export function setThemePreference(next: ThemePreference, { save = true }: { save?: boolean } = {}) {
  const changed = next !== preference
  preference = next
  try {
    localStorage.setItem(STORAGE_KEY, next)
    localStorage.removeItem(LEGACY_KEY)
  } catch {
    // navigation privée : le thème reste appliqué pour cette session
  }
  apply()
  if (save && changed && saver) saver(next)
}

/** Thème mémorisé sur le profil (chargé à la connexion) : appliqué sans être réenregistré. */
export function applyProfileTheme(theme: 'LIGHT' | 'DARK' | 'SYSTEM' | null | undefined) {
  if (!theme) return
  const next = theme.toLowerCase() as ThemePreference
  if (next !== preference) setThemePreference(next, { save: false })
}

export function setThemeSaver(fn: ((preference: ThemePreference) => void) | null) {
  saver = fn
}

function subscribe(listener: Listener) {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}

/** Préférence, thème effectivement affiché et modification — même état dans tous les composants. */
export function useTheme() {
  const current = useSyncExternalStore(subscribe, getThemePreference, getThemePreference)
  const isDark = useSyncExternalStore(subscribe, isDarkTheme, isDarkTheme)
  return { preference: current, dark: isDark, setPreference: setThemePreference }
}

/** Pour les tests : relit le stockage. */
export function resetThemeForTests() {
  preference = readStored()
  apply()
}
