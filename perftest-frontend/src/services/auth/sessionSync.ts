// ============================================================
// Synchronisation de la déconnexion entre onglets et fenêtres du même navigateur.
//
// Les jetons sont gardés par onglet (sessionStorage) : sans ce module, un onglet
// ne savait pas qu'un autre venait de se déconnecter et continuait avec son jeton
// d'accès (jusqu'à 5 min). Ici, l'onglet qui se déconnecte l'annonce ; les autres
// effacent leurs jetons et repartent vers la page de connexion.
//
// Canal entre onglets : voir ../tabSync (BroadcastChannel, sinon événement « storage »).
// Aucun jeton ni information personnelle n'est diffusé, seulement « déconnexion ».
// ============================================================

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
