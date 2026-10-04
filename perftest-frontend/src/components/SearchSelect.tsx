import { KeyboardEvent, useEffect, useId, useMemo, useRef, useState } from 'react'

// ============================================================
// Liste déroulante avec recherche (ex. filtre par scénario) : un champ
// texte filtre les options, flèches haut/bas + Entrée pour choisir, Échap
// pour fermer. La première option vide (« Tous… ») remet le filtre à zéro.
// ============================================================

export interface SearchSelectOption {
  value: string
  label: string
  /** Texte secondaire (ex. application du scénario), aussi pris en compte par la recherche. */
  hint?: string
}

interface SearchSelectProps {
  options: SearchSelectOption[]
  value: string
  onChange: (value: string) => void
  /** Libellé de l'option « aucun filtre » (valeur ''). */
  allLabel: string
  ariaLabel: string
  width?: string
}

export default function SearchSelect({ options, value, onChange, allLabel, ariaLabel, width = '260px' }: SearchSelectProps) {
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)
  const rootRef = useRef<HTMLDivElement | null>(null)
  const listId = useId()

  const selected = options.find((o) => o.value === value) ?? null
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    const all: SearchSelectOption[] = [{ value: '', label: allLabel }, ...options]
    return q ? all.filter((o) => o.value === '' || `${o.label} ${o.hint ?? ''}`.toLowerCase().includes(q)) : all
  }, [options, query, allLabel])

  useEffect(() => {
    if (!open) return
    const onDoc = (e: MouseEvent) => { if (!rootRef.current?.contains(e.target as Node)) setOpen(false) }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [open])

  const choose = (option: SearchSelectOption) => {
    onChange(option.value)
    setOpen(false)
    setQuery('')
  }

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); setOpen(true); setActive((a) => Math.min(filtered.length - 1, a + 1)) }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setActive((a) => Math.max(0, a - 1)) }
    else if (e.key === 'Enter' && open && filtered[active]) { e.preventDefault(); choose(filtered[active]) }
    else if (e.key === 'Escape') { setOpen(false); setQuery('') }
  }

  return (
    <div ref={rootRef} style={{ position: 'relative', width, maxWidth: '100%' }}>
      <div className="pt-search" style={{ width: '100%' }}>
        <i className="bi bi-funnel"></i>
        <input
          type="text"
          role="combobox"
          aria-label={ariaLabel}
          aria-expanded={open}
          aria-controls={listId}
          aria-autocomplete="list"
          placeholder={selected ? selected.label : allLabel}
          value={open ? query : selected?.label ?? ''}
          onFocus={() => { setOpen(true); setActive(0) }}
          onChange={(e) => { setQuery(e.target.value); setOpen(true); setActive(0) }}
          onKeyDown={onKeyDown}
        />
        {value && (
          <button type="button" aria-label="Effacer le filtre" onClick={() => choose({ value: '', label: allLabel })}
            style={{ border: 'none', background: 'none', color: 'var(--pt-text-muted)', padding: 0, cursor: 'pointer' }}>
            <i className="bi bi-x-lg"></i>
          </button>
        )}
      </div>
      {open && (
        <ul id={listId} role="listbox" aria-label={ariaLabel}
          style={{ position: 'absolute', zIndex: 30, top: 'calc(100% + 4px)', left: 0, right: 0, maxHeight: '260px', overflowY: 'auto',
            margin: 0, padding: '4px', listStyle: 'none', background: 'var(--pt-card-bg)', border: '1px solid var(--pt-border)',
            borderRadius: 'var(--pt-radius-sm)', boxShadow: '0 8px 24px rgba(0,0,0,0.12)' }}>
          {filtered.length === 1 && query.trim() && (
            <li style={{ padding: '6px 10px', fontSize: '12.5px', color: 'var(--pt-text-muted)' }}>Aucun résultat</li>
          )}
          {filtered.map((o, i) => (
            <li key={o.value || '__all'} role="option" aria-selected={o.value === value}
              onMouseDown={(e) => { e.preventDefault(); choose(o) }}
              onMouseEnter={() => setActive(i)}
              style={{ padding: '6px 10px', borderRadius: '6px', cursor: 'pointer', fontSize: '13px',
                background: i === active ? 'var(--pt-sidebar-item-hover)' : undefined,
                fontWeight: o.value === value ? 600 : undefined, color: o.value === '' ? 'var(--pt-text-muted)' : 'var(--pt-text)' }}>
              {o.label}
              {o.hint && <span style={{ marginLeft: '6px', fontSize: '11.5px', color: 'var(--pt-text-muted)' }}>{o.hint}</span>}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
