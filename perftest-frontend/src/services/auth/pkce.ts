// ============================================================
// Phase 16 — PKCE (Proof Key for Code Exchange, RFC 7636) pour le flux
// OAuth2 "Authorization Code" avec Keycloak.
//
// Implémenté avec les seules API natives du navigateur (window.crypto) —
// AUCUNE dépendance ajoutée (pas de keycloak-js ni d'aucune librairie OIDC) :
// ce fichier fonctionne immédiatement, sans installation, dès qu'un vrai
// Keycloak est disponible (voir keycloakClient.ts).
// ============================================================

function base64UrlEncode(bytes: Uint8Array): string {
  let binary = ''
  bytes.forEach((b) => { binary += String.fromCharCode(b) })
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

/** Génère un code_verifier aléatoire conforme RFC 7636 (43-128 caractères). */
export function generateCodeVerifier(): string {
  const bytes = new Uint8Array(32)
  crypto.getRandomValues(bytes)
  return base64UrlEncode(bytes)
}

/** Dérive le code_challenge (méthode S256) à partir du code_verifier. */
export async function generateCodeChallenge(verifier: string): Promise<string> {
  const data = new TextEncoder().encode(verifier)
  const digest = await crypto.subtle.digest('SHA-256', data)
  return base64UrlEncode(new Uint8Array(digest))
}

/** Valeur aléatoire opaque (protection CSRF du flux OAuth2 "state"). */
export function generateState(): string {
  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  return base64UrlEncode(bytes)
}
