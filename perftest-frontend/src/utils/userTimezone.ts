import { useSyncExternalStore } from 'react'

// ============================================================
// Fuseau horaire de l'utilisateur : celui de son profil (Paramètres > Mon profil), sinon celui du
// navigateur. SEULE source pour l'affichage des dates et le fuseau proposé d'une nouvelle
// planification.
//  - lu sur le profil à l'ouverture de l'application (MainLayout) ;
//  - mis à jour dès l'enregistrement dans Mon profil : pris en compte sans recharger la page.
// ============================================================

/** Fuseau du navigateur (système de l'ordinateur). */
export function browserTimezone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
  } catch {
    return 'UTC'
  }
}

let profileTimezone: string | null = null
const listeners = new Set<() => void>()

/** Fuseau à utiliser maintenant : celui du profil, sinon celui du navigateur. */
export function getUserTimezone(): string {
  return profileTimezone ?? browserTimezone()
}

/** Fuseau enregistré sur le profil (null : aucun choix, fuseau du navigateur). Valeur invalide ignorée. */
export function setUserTimezone(timezone: string | null | undefined) {
  let next: string | null = null
  if (timezone) {
    try {
      new Intl.DateTimeFormat('fr-FR', { timeZone: timezone })
      next = timezone
    } catch { /* fuseau inconnu de ce navigateur : celui du navigateur */ }
  }
  if (next === profileTimezone) return
  profileTimezone = next
  listeners.forEach((l) => l())
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}

/** Fuseau courant ; le composant se met à jour quand il change. */
export function useUserTimezone(): string {
  return useSyncExternalStore(subscribe, getUserTimezone, getUserTimezone)
}

/** Options de formatage complétées du fuseau de l'utilisateur. */
export function inUserZone<T extends Intl.DateTimeFormatOptions>(options?: T): T & { timeZone: string } {
  return { ...(options ?? {}), timeZone: getUserTimezone() } as T & { timeZone: string }
}
