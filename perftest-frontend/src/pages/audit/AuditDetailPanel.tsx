import { useEffect, useId, useRef } from 'react'
import { Link } from 'react-router-dom'
import type { BackendAuditLogResponse } from '../../types/backendContracts'
import { fullDate } from '../../utils/userDisplay'

// Panneau latéral (icône œil) : tout ce que contient une entrée du journal,
// en lecture seule. Aucune action de modification ni de suppression.

const TARGET_TYPE_LABELS: Record<NonNullable<BackendAuditLogResponse['targetType']>, string> = {
  APPLICATION: 'Application',
  SCENARIO: 'Scénario',
  EXECUTION: 'Exécution',
  SCHEDULE: 'Planification',
  USER: 'Compte utilisateur',
  SETTINGS: 'Paramètres',
}

/** Lien vers l'objet concerné (null si aucun écran ne l'affiche). */
export function targetLink(row: Pick<BackendAuditLogResponse, 'targetType' | 'targetId'>): string | null {
  if (!row.targetType || !row.targetId) return null
  const id = encodeURIComponent(row.targetId)
  switch (row.targetType) {
    case 'APPLICATION': return '/applications'
    case 'SCENARIO': return `/scenarios/${id}/edit`
    case 'EXECUTION': return `/executions/report/${id}`
    case 'SCHEDULE': return `/planification/${id}`
    case 'USER': return `/utilisateurs?tab=utilisateurs&user=${id}`
    case 'SETTINGS': return '/parametres?tab=systeme'
    default: return null
  }
}

/** Navigateur lisible à partir du User-Agent (approximation volontairement simple). */
export function browserLabel(userAgent: string | null): string | null {
  if (!userAgent) return null
  const ua = userAgent
  const browser = /Edg\//.test(ua) ? 'Edge' : /OPR\//.test(ua) ? 'Opera' : /Firefox\//.test(ua) ? 'Firefox'
    : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : null
  const os = /Windows/.test(ua) ? 'Windows' : /Android/.test(ua) ? 'Android' : /(iPhone|iPad)/.test(ua) ? 'iOS'
    : /Mac OS X/.test(ua) ? 'macOS' : /Linux/.test(ua) ? 'Linux' : null
  if (browser && os) return `${browser} sur ${os}`
  return browser ?? os ?? null
}

interface Props {
  entry: BackendAuditLogResponse
  onClose: () => void
}

export default function AuditDetailPanel({ entry, onClose }: Props) {
  const titleId = useId()
  const panelRef = useRef<HTMLElement>(null)

  useEffect(() => {
    panelRef.current?.querySelector<HTMLButtonElement>('.btn-close')?.focus()
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const link = entry.targetExists ? targetLink(entry) : null
  const browser = browserLabel(entry.userAgent)
  const actor = entry.actorType === 'SYSTEM' ? 'Système' : entry.username ?? 'Utilisateur inconnu'

  return (
    <div className="ur-drawer-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose() }}>
      <aside ref={panelRef} className="ur-drawer au-drawer" role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <header className="ur-drawer-head">
          <span className={`au-result big ${entry.result === 'SUCCESS' ? 'ok' : 'ko'}`} aria-hidden="true">
            <i className={`bi ${entry.result === 'SUCCESS' ? 'bi-check-lg' : 'bi-x-lg'}`}></i>
          </span>
          <div className="ur-drawer-id">
            <h2 id={titleId}>{entry.summary}</h2>
            <p>{entry.categoryLabel} · {entry.resultLabel}</p>
          </div>
          <button type="button" className="btn-close" aria-label="Fermer" onClick={onClose}></button>
        </header>

        <div className="ur-drawer-body">
          <dl className="au-facts">
            <dt>Date</dt>
            <dd>{fullDate(entry.date)}</dd>
            <dt>Utilisateur</dt>
            <dd>{actor}{entry.actorSource && <span className="au-muted"> — {entry.actorSource}</span>}</dd>
            <dt>Action</dt>
            <dd>{entry.actionLabel}</dd>
            {entry.targetLabel && (
              <>
                <dt>{entry.targetType ? TARGET_TYPE_LABELS[entry.targetType] : 'Objet'}</dt>
                <dd>
                  {link ? <Link to={link} onClick={onClose}>{entry.targetLabel}</Link> : entry.targetLabel}
                  {!entry.targetExists && entry.targetType !== 'SETTINGS' && <span className="au-muted"> (n’existe plus)</span>}
                </dd>
              </>
            )}
            {entry.detail && (
              <>
                <dt>{entry.legacy ? 'Texte d’origine' : 'Détail'}</dt>
                <dd className="au-pre">{entry.detail}</dd>
              </>
            )}
          </dl>

          {entry.changes.length > 0 && (
            <section>
              <h3 className="au-section-title">Avant → après</h3>
              <table className="au-changes">
                <thead>
                  <tr><th scope="col">Champ</th><th scope="col">Avant</th><th scope="col">Après</th></tr>
                </thead>
                <tbody>
                  {entry.changes.map((c, i) => (
                    <tr key={`${c.label}-${i}`}>
                      <th scope="row">{c.label}</th>
                      <td className="before">{c.before ?? <span className="au-muted">—</span>}</td>
                      <td className="after">{c.after ?? <span className="au-muted">—</span>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </section>
          )}

          <section>
            <h3 className="au-section-title">Contexte technique</h3>
            <dl className="au-facts">
              <dt>Adresse IP</dt>
              <dd>{entry.ipLabel ?? <span className="au-muted">Non renseignée</span>}</dd>
              <dt>Navigateur</dt>
              <dd>
                {browser ?? <span className="au-muted">Non renseigné</span>}
                {entry.userAgent && <span className="au-ua" title={entry.userAgent}>{entry.userAgent}</span>}
              </dd>
              <dt>Identifiant</dt>
              <dd><code className="au-code">{entry.id}</code></dd>
              {entry.targetId && (
                <>
                  <dt>Identifiant de l’objet</dt>
                  <dd><code className="au-code">{entry.targetId}</code></dd>
                </>
              )}
            </dl>
          </section>

          {entry.legacy && (
            <p className="au-legacy">
              <i className="bi bi-info-circle" aria-hidden="true"></i>
              Entrée enregistrée avant l’amélioration du journal : l’objet et l’avant / après n’étaient pas encore conservés.
            </p>
          )}
        </div>
      </aside>
    </div>
  )
}
