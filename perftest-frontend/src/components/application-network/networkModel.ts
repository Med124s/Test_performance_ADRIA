import { BackendNetworkRequest, BackendNetworkResponse, BackendProxyMode } from '../../types/backendContracts'

// ============================================================
// Section « Réseau » d'une application (proxy, autorité de certification TLS) :
// formulaire ↔ requête, validation (mêmes règles que le serveur et les scripts
// de démarrage). Fonctions pures, testées.
// ============================================================

export interface NetworkForm {
  proxyMode: BackendProxyMode
  proxyHost: string
  proxyPort: string
  proxyUsername: string
  /** Vide en modification = mot de passe enregistré conservé. */
  proxyPassword: string
  /** Autorité(s) de certification au format PEM ; vide = confiance de la JVM seule. */
  tlsCaCertificate?: string
}

/** Formulaire d'une NOUVELLE application : aucun proxy par défaut (comme le serveur, voir NetworkSupport.applyOnCreate). */
export const EMPTY_NETWORK_FORM: NetworkForm = { proxyMode: 'NONE', proxyHost: '', proxyPort: '', proxyUsername: '', proxyPassword: '', tlsCaCertificate: '' }

export const PROXY_MODE_LABELS: Record<BackendProxyMode, string> = {
  INHERIT: 'Proxy du serveur',
  NONE: 'Aucun proxy',
  CUSTOM: 'Proxy de l’application',
}

export function networkFormFromResponse(n: BackendNetworkResponse | undefined | null): NetworkForm {
  if (!n) return EMPTY_NETWORK_FORM
  return {
    // Application existante sans mode enregistré : proxy du serveur, comme elle a été créée.
    proxyMode: n.proxyMode ?? 'INHERIT',
    proxyHost: n.proxyHost ?? '',
    proxyPort: n.proxyPort == null ? '' : String(n.proxyPort),
    proxyUsername: n.proxyUsername ?? '',
    proxyPassword: '',
    tlsCaCertificate: n.tlsCaCertificate ?? '',
  }
}

export function networkFormToRequest(f: NetworkForm): BackendNetworkRequest {
  // Absent = aucune autorité propre (le serveur l'efface) : seul un PEM saisi est envoyé.
  const ca = f.tlsCaCertificate?.trim() ? { tlsCaCertificate: f.tlsCaCertificate.trim() } : {}
  if (f.proxyMode !== 'CUSTOM') {
    return { proxyMode: f.proxyMode, proxyHost: null, proxyPort: null, proxyUsername: null, proxyPassword: null, ...ca }
  }
  const username = f.proxyUsername.trim() || null
  return {
    proxyMode: 'CUSTOM',
    proxyHost: f.proxyHost.trim() || null,
    proxyPort: f.proxyPort.trim() ? Number(f.proxyPort.trim()) : null,
    proxyUsername: username,
    // Mot de passe tel que saisi (jamais modifié) ; vide = conservé côté serveur.
    proxyPassword: username && f.proxyPassword ? f.proxyPassword : null,
    ...ca,
  }
}

export type NetworkErrors = Partial<Record<'proxyHost' | 'proxyPort' | 'proxyUsername' | 'tlsCaCertificate', string>>

/** Contrôle rapide du PEM (le serveur vérifie ensuite chaque certificat, comme TlsTrust.parse). */
export function caCertificateProblem(pem: string | undefined): string | undefined {
  const text = (pem ?? '').trim()
  if (!text) return undefined
  if (text.length > 65536) return 'Le texte ne doit pas dépasser 64 Ko.'
  if (text.includes('PRIVATE KEY')) return 'Ce texte contient une clé privée : collez uniquement le certificat public de l’autorité.'
  if (!text.includes('-----BEGIN CERTIFICATE-----') || !text.includes('-----END CERTIFICATE-----')) {
    return 'Format PEM attendu : -----BEGIN CERTIFICATE----- … -----END CERTIFICATE-----.'
  }
  return undefined
}

/** Mêmes règles que le serveur (NetworkProfile.proxyProblem, TlsTrust) et que start.ps1 / start.sh. */
export function validateNetworkForm(f: NetworkForm): NetworkErrors {
  const errors: NetworkErrors = {}
  const ca = caCertificateProblem(f.tlsCaCertificate)
  if (ca) errors.tlsCaCertificate = ca
  if (f.proxyMode !== 'CUSTOM') return errors
  const host = f.proxyHost.trim()
  if (!host) errors.proxyHost = 'Indiquez l’hôte du proxy (ex : proxy.banque.local).'
  else if (host.includes('://') || host.includes('/') || /\s/.test(host)) {
    errors.proxyHost = 'Indiquez seulement le nom ou l’adresse (sans http:// ni chemin).'
  }
  const port = f.proxyPort.trim()
  if (!port) errors.proxyPort = 'Indiquez le port du proxy (ex : 8080).'
  else if (!/^\d+$/.test(port) || Number(port) < 1 || Number(port) > 65535) errors.proxyPort = 'Port entre 1 et 65535.'
  if (f.proxyUsername.includes(':')) errors.proxyUsername = 'L’identifiant ne doit pas contenir « : ».'
  return errors
}
