// Déconnexion synchronisée entre les onglets du même navigateur.

import { createTabChannel } from '../tabSync'

const CHANNEL_NAME = 'loadpilot-auth'
/** Clé du canal de secours (événement « storage ») ; sa valeur change à chaque annonce. */
export const LOGOUT_STORAGE_KEY = 'loadpilot_logout_event'

interface AuthMessage {
  type: 'logout'
  at: number
}

const channel = createTabChannel<AuthMessage>(CHANNEL_NAME, LOGOUT_STORAGE_KEY)

/** Prévient les autres onglets que l'utilisateur vient de se déconnecter. */
export function announceLogout(): void {
  channel.post({ type: 'logout', at: Date.now() })
}

/** Appelle `listener` quand un AUTRE onglet annonce une déconnexion ; renvoie la fonction de désabonnement. */
export function onLogoutAnnounced(listener: () => void): () => void {
  return channel.subscribe((message) => {
    if (message.type === 'logout') listener()
  })
}
