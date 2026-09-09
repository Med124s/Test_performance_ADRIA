// ============================================================
// Client HTTP centralisé — toute communication avec JSON Server
// (la "Fake API") passe par ici. Aucun composant ne doit appeler
// fetch() directement : ils passent par services/api/*.
// ============================================================

const BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:4001'

export class ApiError extends Error {
  status: number
  constructor(message: string, status: number) {
    super(message)
    this.status = status
    this.name = 'ApiError'
  }
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  let response: Response
  try {
    response = await fetch(`${BASE_URL}${path}`, {
      headers: { 'Content-Type': 'application/json' },
      ...options,
    })
  } catch (err) {
    throw new ApiError(
      `Impossible de joindre JSON Server sur ${BASE_URL}. Vérifiez qu'il est bien lancé (npm run server).`,
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

export const http = {
  get: <T>(path: string) => request<T>(path, { method: 'GET' }),
  post: <T>(path: string, body: unknown) =>
    request<T>(path, { method: 'POST', body: JSON.stringify(body) }),
  put: <T>(path: string, body: unknown) =>
    request<T>(path, { method: 'PUT', body: JSON.stringify(body) }),
  patch: <T>(path: string, body: unknown) =>
    request<T>(path, { method: 'PATCH', body: JSON.stringify(body) }),
  delete: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
}

export { BASE_URL }
