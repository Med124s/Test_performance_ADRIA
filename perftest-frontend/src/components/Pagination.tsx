interface PaginationProps {
  page: number
  totalPages: number
  onPageChange: (page: number) => void
  startIndex: number
  endIndex: number
  totalItems: number
  itemLabel: string
  /** Optionnel : sélecteur de taille de page (ex. 5, 10, 25, 50). */
  pageSize?: number
  pageSizeOptions?: number[]
  onPageSizeChange?: (size: number) => void
  /** Optionnel : texte du résumé (remplace « Affichage de … à … sur … »). */
  summary?: string
  /** Optionnel : pages (1..n) marquées d'un repère rouge, avec son libellé accessible. */
  alertPages?: number[]
  alertLabel?: string
}

/** Génère la liste des numéros de page à afficher, avec "..." pour les
 * pages intermédiaires quand il y en a beaucoup (ex: 1 2 3 ... 125). */
function getPageNumbers(current: number, total: number): (number | '...')[] {
  if (total <= 7) return Array.from({ length: total }, (_, i) => i + 1)
  const pages: (number | '...')[] = [1]
  if (current > 3) pages.push('...')
  const start = Math.max(2, current - 1)
  const end = Math.min(total - 1, current + 1)
  for (let p = start; p <= end; p++) pages.push(p)
  if (current < total - 2) pages.push('...')
  pages.push(total)
  return pages
}

export default function Pagination({
  page, totalPages, onPageChange, startIndex, endIndex, totalItems, itemLabel, pageSize, pageSizeOptions, onPageSizeChange,
  summary, alertPages, alertLabel = 'Contient des erreurs',
}: PaginationProps) {
  return (
    <nav className="pt-pagination" style={{ padding: '0.75rem 1.25rem', flexWrap: 'wrap', gap: '0.75rem' }} aria-label="Pagination">
      <div className="d-flex align-items-center gap-3 flex-wrap">
        <span aria-live="polite">
          {totalItems === 0 ? 'Aucun résultat' : summary ?? `Affichage de ${startIndex} à ${endIndex} sur ${totalItems} ${itemLabel}`}
        </span>
        {pageSize !== undefined && pageSizeOptions && onPageSizeChange && (
          <label className="d-flex align-items-center gap-2 mb-0" style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)' }}>
            Par page
            <select
              className="pt-form-control"
              style={{ width: 'auto', padding: '2px 8px', fontSize: '12.5px', height: 'auto' }}
              value={pageSize}
              onChange={(e) => onPageSizeChange(Number(e.target.value))}
              aria-label="Nombre d'éléments par page"
            >
              {pageSizeOptions.map((n) => <option key={n} value={n}>{n}</option>)}
            </select>
          </label>
        )}
      </div>
      <div className="pages">
        <button className="page-btn" title="Précédent" disabled={page <= 1} onClick={() => onPageChange(page - 1)}>
          <i className="bi bi-chevron-left"></i>
        </button>
        {getPageNumbers(page, totalPages).map((p, idx) =>
          p === '...' ? (
            <span key={`ellipsis-${idx}`} className="px-1 text-muted" style={{ fontSize: '12px' }}>...</span>
          ) : (
            <button key={p} className={`page-btn ${p === page ? 'active' : ''}`} aria-current={p === page ? 'page' : undefined} onClick={() => onPageChange(p)}
              style={alertPages?.includes(p) ? { position: 'relative' } : undefined}
              aria-label={alertPages?.includes(p) ? `Page ${p} — ${alertLabel}` : undefined}
              title={alertPages?.includes(p) ? alertLabel : undefined}>
              {p}
              {alertPages?.includes(p) && <span className="pt-page-alert" aria-hidden="true"></span>}
            </button>
          )
        )}
        <button className="page-btn" title="Suivant" disabled={page >= totalPages} onClick={() => onPageChange(page + 1)}>
          <i className="bi bi-chevron-right"></i>
        </button>
      </div>
    </nav>
  )
}
