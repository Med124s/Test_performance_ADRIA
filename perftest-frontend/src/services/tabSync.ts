// ============================================================
// Messages entre onglets et fenêtres du même navigateur (même origine).
//
// BroadcastChannel quand le navigateur le permet ; sinon un événement « storage »
// (localStorage) : écrire une clé prévient tous les AUTRES onglets. Un onglet ne
// reçoit jamais ses propres messages. Utilisé par la déconnexion (sessionSync) et
// par le compteur des notifications non lues (unreadNotifications).
// Ne jamais y faire passer de jeton ni d'information personnelle.
// ============================================================

export interface TabChannel<T extends { type: string }> {
  /** Envoie `message` aux autres onglets. */
  post(message: T): void
  /** Appelle `listener` pour chaque message d'un AUTRE onglet ; renvoie la fonction de désabonnement. */
  subscribe(listener: (message: T) => void): () => void
}

export function createTabChannel<T extends { type: string }>(name: string, storageKey: string): TabChannel<T> {
  let channel: BroadcastChannel | null | undefined
  const getChannel = (): BroadcastChannel | null => {
    if (channel === undefined) {
      try {
        channel = typeof BroadcastChannel === 'function' ? new BroadcastChannel(name) : null
      } catch {
        channel = null
      }
    }
    return channel
  }

  return {
    post(message) {
      const bc = getChannel()
      if (bc) {
        try {
          bc.postMessage(message)
          return
        } catch {
          // canal fermé : on passe au canal de secours
        }
      }
      try {
        localStorage.setItem(storageKey, JSON.stringify(message))
        localStorage.removeItem(storageKey)
      } catch {
        // stockage indisponible : les autres onglets se mettront à jour d'eux-mêmes plus tard
      }
    },
    subscribe(listener) {
      const bc = getChannel()
      const onMessage = (event: MessageEvent<T>) => {
        if (event.data && typeof event.data.type === 'string') listener(event.data)
      }
      // Le canal de secours est toujours écouté : un onglet sans BroadcastChannel peut l'utiliser.
      const onStorage = (event: StorageEvent) => {
        if (event.key !== storageKey || !event.newValue) return
        try {
          const message = JSON.parse(event.newValue) as T
          if (message && typeof message.type === 'string') listener(message)
        } catch {
          // valeur illisible : ignorée
        }
      }
      bc?.addEventListener('message', onMessage)
      window.addEventListener('storage', onStorage)
      return () => {
        bc?.removeEventListener('message', onMessage)
        window.removeEventListener('storage', onStorage)
      }
    },
  }
}
