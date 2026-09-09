import { useEffect, useMemo, useState } from 'react'

/** Pagination générique côté client : découpe `items` par pages de
 * `pageSize`, et revient à la page 1 dès que le nombre total d'éléments
 * change (nouvelle recherche/filtre) pour éviter une page vide. */
export function usePagination<T>(items: T[], pageSize = 10) {
  const [page, setPage] = useState(1)
  const totalItems = items.length
  const totalPages = Math.max(1, Math.ceil(totalItems / pageSize))

  useEffect(() => {
    setPage(1)
  }, [totalItems])

  const safePage = Math.min(page, totalPages)

  const pageItems = useMemo(() => {
    const start = (safePage - 1) * pageSize
    return items.slice(start, start + pageSize)
  }, [items, safePage, pageSize])

  const startIndex = totalItems === 0 ? 0 : (safePage - 1) * pageSize + 1
  const endIndex = Math.min(safePage * pageSize, totalItems)

  return { page: safePage, setPage, totalPages, pageItems, startIndex, endIndex, totalItems }
}
