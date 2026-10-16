import { useSyncExternalStore } from 'react'
import { notificationsBackendApi } from '../api/notificationsBackend'
import { createTabChannel } from '../tabSync'

// ============================================================
// Nombre de notifications non lues : UNE seule valeur pour la cloche, la page « Notifications »
// et les autres onglets.
//  - relu sur le serveur toutes les 15 s, au retour sur l'onglet et peu après la fin d'une
//    exécution suivie dans cet onglet ;
//  - baisse immédiate quand l'utilisateur marque lu (une, toutes, ouverture, suppression),
//    sans attendre le serveur, et annoncée aux autres onglets (même canal que la déconnexion) ;
//  - « arrivals » augmente à chaque hausse du nombre (nouvelle notification) : la cloche
//    joue son animation une fois par hausse.
// ============================================================

export const UNREAD_POLL_INTERVAL_MS = 15000

interface UnreadMessage {
  type: 'unread'
  count: number
  at: number
}

export interface UnreadState {
  /** null tant que le serveur n'a pas répondu. */
  count: number | null
  /** Nombre de hausses constatées depuis l'ouverture (nouvelles notifications). */
  arrivals: number
}

const channel = createTabChannel<UnreadMessage>('loadpilot-notifications', 'loadpilot_notifications_event')

let state: UnreadState = { count: null, arrivals: 0 }
/** Change à chaque modification faite ici ou dans un autre onglet : une réponse plus ancienne est ignorée. */
let version = 0
const listeners = new Set<() => void>()
const changeListeners = new Set<() => void>()
let stopChannel: (() => void) | null = null

function set(count: number, origin: 'server' | 'local' | 'remote') {
  const previous = state.count
  const safe = Math.max(0, Math.round(count))
  const arrived = previous != null && safe > previous
  if (safe === previous) return
  state = { count: safe, arrivals: state.arrivals + (arrived ? 1 : 0) }
  listeners.forEach((l) => l())
  if (origin === 'local') channel.post({ type: 'unread', count: safe, at: Date.now() })
  // La liste affichée est à relire : nouvelle notification, ou changement fait dans un autre onglet.
  if (arrived || origin === 'remote') changeListeners.forEach((l) => l())
}

function ensureChannel() {
  if (stopChannel) return
  stopChannel = channel.subscribe((message) => {
    if (message.type !== 'unread' || typeof message.count !== 'number') return
    version++
    set(message.count, 'remote')
  })
}

function subscribe(listener: () => void) {
  ensureChannel()
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}

const getState = () => state

/** Nombre de non lues et nombre d'arrivées ; le composant se met à jour à chaque changement. */
export function useUnreadNotifications(): UnreadState {
  return useSyncExternalStore(subscribe, getState, getState)
}

export function getUnreadCount(): number | null {
  return state.count
}

/** Relit le nombre sur le serveur (ignoré si une modification plus récente a eu lieu entre-temps). */
export function refreshUnread(): Promise<void> {
  ensureChannel()
  const started = version
  return notificationsBackendApi.unreadCount()
    .then((r) => { if (started === version) set(r.count, 'server') })
    .catch(() => { /* compteur non critique : réessayé au prochain passage */ })
}

/** Relit le nombre un peu plus tard (la notification est créée juste après la fin d'une exécution). */
export function refreshUnreadSoon() {
  for (const delay of [1500, 5000]) window.setTimeout(() => { void refreshUnread() }, delay)
}

/** `n` notifications viennent d'être lues (ou supprimées non lues) ici : baisse immédiate. */
export function noteNotificationsRead(n = 1) {
  if (state.count == null || n <= 0) return
  version++
  set(state.count - n, 'local')
}

/** « Tout marquer comme lu » : le compteur passe à 0 tout de suite. */
export function noteAllNotificationsRead() {
  version++
  set(0, 'local')
}

/** Appelle `listener` quand la liste affichée doit être relue (arrivée, autre onglet). */
export function onNotificationsChanged(listener: () => void): () => void {
  ensureChannel()
  changeListeners.add(listener)
  return () => { changeListeners.delete(listener) }
}

/** Interrogation régulière + retour sur l'onglet ; renvoie la fonction d'arrêt. */
export function startUnreadPolling(intervalMs = UNREAD_POLL_INTERVAL_MS): () => void {
  void refreshUnread()
  const timer = window.setInterval(() => { void refreshUnread() }, intervalMs)
  const onVisible = () => { if (document.visibilityState === 'visible') void refreshUnread() }
  document.addEventListener('visibilitychange', onVisible)
  window.addEventListener('focus', onVisible)
  return () => {
    window.clearInterval(timer)
    document.removeEventListener('visibilitychange', onVisible)
    window.removeEventListener('focus', onVisible)
  }
}

export function resetUnreadForTests() {
  state = { count: null, arrivals: 0 }
  version++
  stopChannel?.()
  stopChannel = null
  changeListeners.clear()
}
