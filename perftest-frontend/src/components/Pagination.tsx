interface PaginationProps {
  page: number
  totalPages: number
  onPageChange: (page: number) => void
  startIndex: number
  endIndex: number
  totalItems: number
  itemLabel: string
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

export default function Pagination({ page, totalPages, onPageChange, startIndex, endIndex, totalItems, itemLabel }: PaginationProps) {
  return (
    <div className="pt-pagination" style={{ padding: '0.75rem 1.25rem' }}>
      <span>Affichage de {startIndex} à {endIndex} sur {totalItems} {itemLabel}</span>
      <div className="pages">
        <button className="page-btn" title="Précédent" disabled={page <= 1} onClick={() => onPageChange(page - 1)}>
          <i className="bi bi-chevron-left"></i>
        </button>
        {getPageNumbers(page, totalPages).map((p, idx) =>
          p === '...' ? (
            <span key={`ellipsis-${idx}`} className="px-1 text-muted" style={{ fontSize: '12px' }}>...</span>
          ) : (
            <button key={p} className={`page-btn ${p === page ? 'active' : ''}`} onClick={() => onPageChange(p)}>
              {p}
            </button>
          )
        )}
        <button className="page-btn" title="Suivant" disabled={page >= totalPages} onClick={() => onPageChange(page + 1)}>
          <i className="bi bi-chevron-right"></i>
        </button>
      </div>
    </div>
  )
}
