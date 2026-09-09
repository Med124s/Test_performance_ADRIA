import { useCallback, useEffect, useState } from 'react'

/**
 * Charge une liste de données depuis un service API (services/api/*) et
 * expose loading/error/refetch — pattern réutilisé par toutes les pages
 * qui consomment JSON Server, pour éviter de dupliquer cette logique.
 */
export function useApiList<T>(fetcher: () => Promise<T[]>, deps: unknown[] = []) {
  const [data, setData] = useState<T[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const refetch = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const result = await fetcher()
      setData(result)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Erreur de chargement')
    } finally {
      setLoading(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)

  useEffect(() => {
    refetch()
  }, [refetch])

  return { data, loading, error, refetch, setData }
}

/** Même principe pour une ressource unique (ex: un scénario par ID). */
export function useApiItem<T>(fetcher: () => Promise<T>, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const refetch = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const result = await fetcher()
      setData(result)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Erreur de chargement')
      setData(null)
    } finally {
      setLoading(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)

  useEffect(() => {
    refetch()
  }, [refetch])

  return { data, loading, error, refetch, setData }
}
