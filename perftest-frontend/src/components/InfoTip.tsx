import './formTabs.css'

/** Icône « i » avec une explication courte, au survol et au clavier (remplace un paragraphe d'aide). */
export default function InfoTip({ text }: { text: string }) {
  return (
    <span className="ft-info" tabIndex={0} role="img" aria-label={text} title={text}>
      <i className="bi bi-info-circle" aria-hidden="true"></i>
    </span>
  )
}
