import { ReactNode, useEffect, useMemo, useState } from 'react'
import Pagination from './Pagination'

// ============================================================
// Pagination des tableaux d'étapes (suivi en direct et rapport final) :
// 10 étapes par page au-delà de 10, repère rouge sur les pages qui contiennent
// des échecs, option « Afficher seulement les étapes en échec ». La page
// courante est conservée quand les chiffres changent (mise à jour en direct).
// ============================================================

export const STEP_PAGE_SIZE = 10

export interface IndexedStep<T> {
  item: T
  /** Numéro de l'étape dans le scénario (1..n), conservé après filtrage. */
  number: number
}

export function useStepPaging<T>(items: T[], hasFailure: (item: T) => boolean, pageSize = STEP_PAGE_SIZE) {
  const [page, setPage] = useState(1)
  const [onlyFailed, setOnlyFailed] = useState(false)

  const indexed = useMemo(() => items.map((item, i) => ({ item, number: i + 1 })), [items])
  const visible = useMemo(
    () => (onlyFailed ? indexed.filter((s) => hasFailure(s.item)) : indexed),
    // hasFailure : fonction pure fournie par l'appelant (non suivie comme dépendance).
    [indexed, onlyFailed],
  )
  const pageCount = Math.max(1, Math.ceil(visible.length / pageSize))
  const current = Math.min(page, pageCount)

  // Reste sur la page regardée ; seulement ramenée dans les bornes si elle n'existe plus.
  useEffect(() => { if (page > pageCount) setPage(pageCount) }, [page, pageCount])

  const start = (current - 1) * pageSize
  const pageItems: IndexedStep<T>[] = visible.slice(start, start + pageSize)
  const failedPages = useMemo(() => {
    const pages: number[] = []
    for (let p = 0; p < pageCount; p++) {
      if (visible.slice(p * pageSize, (p + 1) * pageSize).some((s) => hasFailure(s.item))) pages.push(p + 1)
    }
    return pages
  }, [visible, pageCount, pageSize])

  return {
    page: current,
    setPage,
    pageCount,
    pageItems,
    visibleCount: visible.length,
    totalCount: items.length,
    failedCount: indexed.filter((s) => hasFailure(s.item)).length,
    from: visible.length === 0 ? 0 : start + 1,
    to: Math.min(start + pageSize, visible.length),
    failedPages,
    onlyFailed,
    setOnlyFailed: (value: boolean) => { setOnlyFailed(value); setPage(1) },
    paginated: visible.length > pageSize,
  }
}

type Paging = ReturnType<typeof useStepPaging<unknown>>

/** Case « Afficher seulement les étapes en échec » (à placer dans l'en-tête du tableau). */
export function OnlyFailedToggle({ paging, id }: { paging: Pick<Paging, 'onlyFailed' | 'setOnlyFailed' | 'failedCount'>; id: string }) {
  return (
    <label className="d-inline-flex align-items-center gap-2 mb-0" htmlFor={id} style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)', cursor: 'pointer' }}>
      <input id={id} type="checkbox" className="form-check-input m-0" checked={paging.onlyFailed}
        onChange={(e) => paging.setOnlyFailed(e.target.checked)} />
      Afficher seulement les étapes en échec{paging.failedCount > 0 ? ` (${paging.failedCount})` : ''}
    </label>
  )
}

/** Pied du tableau : Précédent / numéros / Suivant et « Étapes 1 à 10 sur 25 » (au-delà de 10 étapes). */
export function StepPagerFooter({ paging }: { paging: Paging }) {
  if (!paging.paginated) return null
  const scope = paging.onlyFailed ? ' en échec' : ''
  return (
    <Pagination
      page={paging.page}
      totalPages={paging.pageCount}
      onPageChange={paging.setPage}
      startIndex={paging.from}
      endIndex={paging.to}
      totalItems={paging.visibleCount}
      itemLabel="étapes"
      summary={`Étapes${scope} ${paging.from} à ${paging.to} sur ${paging.visibleCount}`}
      alertPages={paging.failedPages}
      alertLabel="contient des étapes en échec"
    />
  )
}

/** Message quand le filtre « en échec » ne laisse aucune étape. */
export function NoFailedSteps({ colSpan }: { colSpan: number }): ReactNode {
  return <tr><td colSpan={colSpan} className="text-center text-muted py-3">Aucune étape en échec.</td></tr>
}
