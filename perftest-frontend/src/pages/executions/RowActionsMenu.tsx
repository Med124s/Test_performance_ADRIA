import { useEffect, useRef, useState } from 'react'

// Menu « ⋯ » d'une ligne : Relancer, Annuler (exécution active), Supprimer (en rouge).
// Positionné en coordonnées fixes (jamais coupé par le défilement horizontal du tableau) ;
// fermé par un clic à l'extérieur, Échap ou un défilement.

export interface RowAction {
  label: string
  icon: string
  onSelect: () => void
  danger?: boolean
  disabled?: boolean
  /** Raison affichée en infobulle quand l'action est désactivée. */
  reason?: string
}

const MENU_WIDTH = 190

export default function RowActionsMenu({ actions, label, width = MENU_WIDTH }: { actions: RowAction[]; label: string; width?: number }) {
  const [pos, setPos] = useState<{ top: number; left: number } | null>(null)
  const button = useRef<HTMLButtonElement | null>(null)
  const menu = useRef<HTMLDivElement | null>(null)
  const open = pos != null

  useEffect(() => {
    if (!open) return
    const close = () => setPos(null)
    const onDown = (e: MouseEvent) => {
      const t = e.target as Node
      if (!menu.current?.contains(t) && !button.current?.contains(t)) close()
    }
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') { close(); button.current?.focus() } }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    window.addEventListener('scroll', close, true)
    window.addEventListener('resize', close)
    menu.current?.querySelector<HTMLButtonElement>('button:not([disabled])')?.focus()
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
      window.removeEventListener('scroll', close, true)
      window.removeEventListener('resize', close)
    }
  }, [open])

  const toggle = () => {
    if (open || !button.current) { setPos(null); return }
    const r = button.current.getBoundingClientRect()
    const left = Math.max(8, Math.min(r.right - width, window.innerWidth - width - 8))
    const below = r.bottom + 4
    const top = below + 140 > window.innerHeight ? Math.max(8, r.top - 4 - 36 * actions.length - 8) : below
    setPos({ top, left })
  }

  if (actions.length === 0) return null
  return (
    <>
      <button ref={button} type="button" className="ex-icon-btn" aria-haspopup="menu" aria-expanded={open} aria-label={label}
        title="Plus d’actions" onClick={toggle}>
        <i className="bi bi-three-dots" aria-hidden="true"></i>
      </button>
      {open && (
        <div ref={menu} className="ex-menu-list" role="menu" style={{ top: pos.top, left: pos.left, width }}>
          {actions.map((a) => (
            <button key={a.label} type="button" role="menuitem" className={`ex-menu-item ${a.danger ? 'danger' : ''}`} disabled={a.disabled}
              title={a.disabled ? a.reason : undefined}
              onClick={() => { setPos(null); a.onSelect() }}>
              <i className={`bi ${a.icon}`} aria-hidden="true"></i>{a.label}
            </button>
          ))}
        </div>
      )}
    </>
  )
}
