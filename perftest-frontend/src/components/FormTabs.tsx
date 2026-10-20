import { KeyboardEvent, ReactNode, useId, useRef } from 'react'
import './formTabs.css'

// ============================================================
// Onglets d'un formulaire (modale) : état géré par le parent, jamais
// dans l'URL (voir PageTabs pour les onglets de page). Accessibles :
// tablist / tab / tabpanel, flèches gauche / droite, Début / Fin.
// Tous les panneaux restent montés (inactifs masqués) : une saisie ou
// un résultat de test d'un onglet est conservé en changeant d'onglet.
// ============================================================

export interface FormTab {
  id: string
  label: string
  icon: string
  /** Point sur l'onglet : réglage non par défaut (« configured ») ou champ en erreur (« error »). */
  marker?: 'configured' | 'error' | null
  content: ReactNode
}

const MARKER_LABELS = { configured: 'configuré', error: 'à corriger' } as const

export default function FormTabs({ tabs, active, onChange, label }: {
  tabs: FormTab[]
  active: string
  onChange: (id: string) => void
  label: string
}) {
  const baseId = useId()
  const refs = useRef<Record<string, HTMLButtonElement | null>>({})
  const current = tabs.find((t) => t.id === active) ?? tabs[0]

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const i = tabs.findIndex((t) => t.id === current.id)
    const target = e.key === 'ArrowRight' ? tabs[(i + 1) % tabs.length]
      : e.key === 'ArrowLeft' ? tabs[(i - 1 + tabs.length) % tabs.length]
        : e.key === 'Home' ? tabs[0] : e.key === 'End' ? tabs[tabs.length - 1] : null
    if (!target) return
    e.preventDefault()
    onChange(target.id)
    refs.current[target.id]?.focus()
  }

  return (
    <div className="ft-root">
      <div className="ft-tabs" role="tablist" aria-label={label} onKeyDown={onKeyDown}>
        {tabs.map((t) => {
          const selected = t.id === current.id
          return (
            <button key={t.id} ref={(el) => { refs.current[t.id] = el }} type="button" role="tab" id={`${baseId}-${t.id}`}
              aria-selected={selected} aria-controls={`${baseId}-${t.id}-panel`} tabIndex={selected ? 0 : -1}
              className={`ft-tab ${selected ? 'active' : ''}`} onClick={() => onChange(t.id)}>
              <i className={`bi ${t.icon}`} aria-hidden="true"></i>
              {t.label}
              {t.marker && (
                <span className={`ft-marker ${t.marker}`} data-testid={`tab-marker-${t.id}`} title={MARKER_LABELS[t.marker]}>
                  <span className="visually-hidden"> ({MARKER_LABELS[t.marker]})</span>
                </span>
              )}
            </button>
          )
        })}
      </div>
      {tabs.map((t) => (
        <div key={t.id} role="tabpanel" id={`${baseId}-${t.id}-panel`} aria-labelledby={`${baseId}-${t.id}`}
          hidden={t.id !== current.id} className="ft-panel">
          {t.content}
        </div>
      ))}
    </div>
  )
}
