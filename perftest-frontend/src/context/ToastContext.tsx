import { createContext, useCallback, useContext, useRef, useState, ReactNode } from 'react'

export type ToastType = 'success' | 'danger' | 'warning' | 'info'

export interface Toast {
  id: number
  type: ToastType
  message: string
}

interface ToastContextValue {
  showToast: (message: string, type?: ToastType) => void
}

const ToastContext = createContext<ToastContextValue | undefined>(undefined)

const TOAST_DURATION_MS = 4000

const typeMeta: Record<ToastType, { icon: string; color: string; bg: string }> = {
  success: { icon: 'bi-check-circle-fill', color: 'var(--pt-success)', bg: 'var(--pt-success-light)' },
  danger: { icon: 'bi-x-circle-fill', color: 'var(--pt-danger)', bg: 'var(--pt-danger-light)' },
  warning: { icon: 'bi-exclamation-triangle-fill', color: 'var(--pt-warning)', bg: 'var(--pt-warning-light)' },
  info: { icon: 'bi-info-circle-fill', color: 'var(--pt-info)', bg: 'var(--pt-info-light)' },
}

/**
 * Alertes ponctuelles ("toasts") affichées après une action utilisateur
 * (ajout / modification / suppression / opération...) — distinctes du
 * centre de notifications persistant (cloche), qui reste géré à part.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([])
  const nextId = useRef(1)

  const dismissToast = useCallback((id: number) => {
    setToasts((prev) => prev.filter((t) => t.id !== id))
  }, [])

  const showToast = useCallback(
    (message: string, type: ToastType = 'success') => {
      const id = nextId.current++
      setToasts((prev) => [...prev, { id, type, message }])
      setTimeout(() => dismissToast(id), TOAST_DURATION_MS)
    },
    [dismissToast]
  )

  return (
    <ToastContext.Provider value={{ showToast }}>
      {children}
      <div
        style={{
          position: 'fixed',
          bottom: '1.5rem',
          right: '1.5rem',
          zIndex: 10000,
          display: 'flex',
          flexDirection: 'column',
          gap: '0.5rem',
          maxWidth: '360px',
        }}
      >
        {toasts.map((t) => {
          const meta = typeMeta[t.type]
          return (
            <div
              key={t.id}
              className="d-flex align-items-center gap-2"
              style={{
                background: 'var(--pt-card-bg)',
                border: `1px solid ${meta.color}`,
                borderLeft: `4px solid ${meta.color}`,
                borderRadius: 'var(--pt-radius-sm)',
                padding: '0.75rem 1rem',
                boxShadow: 'var(--pt-shadow-md)',
                fontSize: '13px',
                color: 'var(--pt-text)',
                animation: 'pt-toast-in 0.2s ease',
              }}
            >
              <span
                style={{
                  width: '26px',
                  height: '26px',
                  borderRadius: '50%',
                  background: meta.bg,
                  color: meta.color,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  flexShrink: 0,
                  fontSize: '13px',
                }}
              >
                <i className={`bi ${meta.icon}`}></i>
              </span>
              <span className="flex-grow-1">{t.message}</span>
              <button
                onClick={() => dismissToast(t.id)}
                style={{ background: 'none', border: 'none', color: 'var(--pt-text-muted)', cursor: 'pointer', fontSize: '16px', lineHeight: 1, padding: 0 }}
              >
                <i className="bi bi-x"></i>
              </button>
            </div>
          )
        })}
      </div>
    </ToastContext.Provider>
  )
}

export function useToast() {
  const ctx = useContext(ToastContext)
  if (!ctx) throw new Error('useToast must be used within a ToastProvider')
  return ctx
}
