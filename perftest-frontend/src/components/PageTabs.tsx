import { KeyboardEvent, ReactNode, useId, useRef } from 'react'
import { useSearchParams } from 'react-router-dom'

// ============================================================
// Onglets de page gardés dans l'URL (?tab=…) : utilisés par « Paramètres »
// et « Utilisateurs et rôles ». Accessibles : rôles ARIA tablist / tab /
// tabpanel, flèches gauche / droite, Début / Fin. Un onglet inconnu (ou
// non autorisé pour le rôle) retombe sur le premier.
// ============================================================

export interface PageTab {
  id: string
  label: string
  icon: string
  content: ReactNode
}

export default function PageTabs({ tabs, label }: { tabs: PageTab[]; label: string }) {
  const [params, setParams] = useSearchParams()
  const baseId = useId()
  const refs = useRef<Record<string, HTMLButtonElement | null>>({})
  const requested = params.get('tab')
  const current = tabs.find((t) => t.id === requested) ?? tabs[0]

  const select = (id: string, focus = false) => {
    setParams((prev) => {
      const next = new URLSearchParams(prev)
      next.set('tab', id)
      return next
    }, { replace: true })
    if (focus) refs.current[id]?.focus()
  }

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const i = tabs.findIndex((t) => t.id === current.id)
    const target = e.key === 'ArrowRight' ? tabs[(i + 1) % tabs.length]
      : e.key === 'ArrowLeft' ? tabs[(i - 1 + tabs.length) % tabs.length]
        : e.key === 'Home' ? tabs[0] : e.key === 'End' ? tabs[tabs.length - 1] : null
    if (!target) return
    e.preventDefault()
    select(target.id, true)
  }

  return (
    <>
      <div className="pt-card ex-card mb-3">
        <div className="ex-tabs" role="tablist" aria-label={label} onKeyDown={onKeyDown}>
          {tabs.map((t) => {
            const selected = t.id === current.id
            return (
              <button key={t.id} ref={(el) => { refs.current[t.id] = el }} type="button" role="tab" id={`${baseId}-${t.id}`}
                aria-selected={selected} aria-controls={`${baseId}-${t.id}-panel`} tabIndex={selected ? 0 : -1}
                className={`ex-tab ${selected ? 'active' : ''}`} onClick={() => select(t.id)}>
                <i className={`bi ${t.icon}`} aria-hidden="true"></i>{t.label}
              </button>
            )
          })}
        </div>
      </div>
      <div role="tabpanel" id={`${baseId}-${current.id}-panel`} aria-labelledby={`${baseId}-${current.id}`}>
        {current.content}
      </div>
    </>
  )
}
