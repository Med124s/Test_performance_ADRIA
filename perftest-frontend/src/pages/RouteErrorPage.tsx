import { Link, isRouteErrorResponse, useRouteError } from 'react-router-dom'

// ============================================================
// Page d'erreur des routes (errorElement) : remplace l'écran technique
// « Unexpected Application Error » de React Router par un message clair en
// français, le détail technique replié, et des actions pour repartir
// (« Retour aux exécutions », recharger). Affichée dans la mise en page
// (menu conservé) pour les pages, en pleine page sinon.
// ============================================================

function describe(error: unknown): { title: string; message: string; detail: string | null } {
  if (isRouteErrorResponse(error)) {
    if (error.status === 404) {
      return { title: 'Page introuvable', message: 'Cette adresse ne correspond à aucune page de LoadPilot.', detail: null }
    }
    return { title: `Erreur ${error.status}`, message: 'La page n’a pas pu être chargée.', detail: error.statusText || null }
  }
  const detail = error instanceof Error ? `${error.name} : ${error.message}` : error == null ? null : String(error)
  return {
    title: 'Cette page a rencontré un problème',
    message: 'Une erreur inattendue l’a empêchée de s’afficher. Vos données ne sont pas touchées : revenez à la liste des exécutions ou rechargez la page.',
    detail,
  }
}

/** Page d'erreur d'une route (errorElement) : lit l'erreur levée par la page. */
export default function RouteErrorPage({ fullPage = false }: { fullPage?: boolean }) {
  const error = useRouteError()
  if (error && !isRouteErrorResponse(error)) console.error(error)
  return <ErrorView {...describe(error)} fullPage={fullPage} />
}

/** Adresse inconnue (route « * ») : même présentation, sans erreur levée. */
export function NotFoundPage() {
  return <ErrorView title="Page introuvable" message="Cette adresse ne correspond à aucune page de LoadPilot." detail={null} />
}

function ErrorView({ title, message, detail, fullPage = false }: { title: string; message: string; detail: string | null; fullPage?: boolean }) {
  return (
    <div className={fullPage ? 'd-flex align-items-center justify-content-center' : 'pt-content'}
      style={fullPage ? { minHeight: '100vh', padding: '1rem', background: 'var(--pt-bg)' } : undefined}>
      <div className="pt-card" role="alert" style={{ maxWidth: 620, margin: fullPage ? 0 : '2rem auto', textAlign: 'center', padding: '2rem 1.5rem' }}>
        <i className="bi bi-exclamation-octagon" style={{ fontSize: 40, color: 'var(--pt-danger)' }} aria-hidden="true"></i>
        <h1 style={{ fontSize: 20, fontWeight: 700, margin: '0.75rem 0 0.5rem' }}>{title}</h1>
        <p style={{ color: 'var(--pt-text-muted)', fontSize: 14, marginBottom: '1.25rem' }}>{message}</p>
        <div className="d-flex justify-content-center gap-2 flex-wrap">
          <Link to="/executions" className="pt-btn-primary" style={{ textDecoration: 'none' }}><i className="bi bi-arrow-left"></i> Retour aux exécutions</Link>
          <button type="button" className="pt-btn-outline" onClick={() => window.location.reload()}>
            <i className="bi bi-arrow-clockwise"></i> Recharger la page
          </button>
        </div>
        {detail && (
          <details style={{ marginTop: '1.25rem', textAlign: 'left', fontSize: 12, color: 'var(--pt-text-muted)' }}>
            <summary style={{ cursor: 'pointer' }}>Détail technique (pour le support)</summary>
            <pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word', marginTop: '0.5rem', fontSize: 12 }}>{detail}</pre>
          </details>
        )}
      </div>
    </div>
  )
}
