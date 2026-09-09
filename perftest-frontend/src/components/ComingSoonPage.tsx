interface ComingSoonPageProps {
  title: string
  description: string
  icon: string
}

/**
 * Placeholder partagé pour les sections mises en pause (refonte à venir) —
 * garde le header de page standard mais remplace tout le contenu par un
 * état vide explicite, plutôt que de laisser une ancienne UI à moitié à jour.
 */
function ComingSoonPage({ title, description, icon }: ComingSoonPageProps) {
  return (
    <div className="pt-content">
      <div className="pt-page-header">
        <div className="page-title">
          <h1>{title}</h1>
          <p>{description}</p>
        </div>
      </div>

      <div className="pt-card">
        <div className="pt-empty-state" style={{ padding: '4rem 1.5rem' }}>
          <i className={`bi ${icon}`} style={{ fontSize: '40px', color: 'var(--pt-text-light)' }}></i>
          <p style={{ fontSize: '15px', fontWeight: 600, color: 'var(--pt-text)' }}>Bientôt disponible</p>
          <p>Cette section est en cours de refonte et sera de nouveau disponible prochainement.</p>
        </div>
      </div>
    </div>
  )
}

export default ComingSoonPage
