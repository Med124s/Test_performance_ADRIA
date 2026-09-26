// ============================================================
// Client HTTP centralisé. Aucun composant ne doit appeler fetch()
// directement : ils passent par services/api/*.
//
// P1-O — le client JSON Server (`http`, VITE_API_URL) a été retiré avec le
// reste du legacy : `springHttp` (backend Spring Boot réel,
// VITE_SPRING_API_URL) est désormais le seul client HTTP de l'application.
//
// Ajoute automatiquement `Authorization: Bearer <token>` quand un vrai
// token Keycloak est disponible (voir services/auth/keycloakClient.ts).
// ============================================================

import { getAccessToken } from '../auth/keycloakClient'

export class ApiError extends Error {
  status: number
  constructor(message: string, status: number) {
    super(message)
    this.status = status
    this.name = 'ApiError'
  }
}

function createClient(baseUrl: string, serviceLabel: string) {
  async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
    const token = await getAccessToken()
    const headers: Record<string, string> = { 'Content-Type': 'application/json' }
    if (token) {
      headers['Authorization'] = `Bearer ${token}`
    }

    let response: Response
    try {
      response = await fetch(`${baseUrl}${path}`, {
        headers,
        ...options,
      })
    } catch (err) {
      throw new ApiError(
        `Impossible de joindre ${serviceLabel} sur ${baseUrl}. Vérifiez qu'il est bien lancé.`,
        0
      )
    }

    if (!response.ok) {
      let message = `Erreur ${response.status} sur ${path}`
      try {
        const body = await response.json()
        if (body?.message) message = body.message
      } catch {
        // pas de corps JSON exploitable
      }
      throw new ApiError(message, response.status)
    }

    if (response.status === 204) {
      return undefined as T
    }
    return response.json() as Promise<T>
  }

  return {
    get: <T>(path: string) => request<T>(path, { method: 'GET' }),
    post: <T>(path: string, body?: unknown) =>
      request<T>(path, { method: 'POST', body: body !== undefined ? JSON.stringify(body) : undefined }),
    put: <T>(path: string, body: unknown) =>
      request<T>(path, { method: 'PUT', body: JSON.stringify(body) }),
    patch: <T>(path: string, body: unknown) =>
      request<T>(path, { method: 'PATCH', body: JSON.stringify(body) }),
    delete: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
  }
}

export const SPRING_API_URL = import.meta.env.VITE_SPRING_API_URL || 'http://localhost:8080'
export const springHttp = createClient(SPRING_API_URL, 'le serveur LoadPilot (Spring Boot)')
