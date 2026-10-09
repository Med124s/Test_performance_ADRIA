import { useTheme, ThemePreference } from '../../utils/theme'

// Apparence : trois choix, le même état que le bouton de la barre latérale (utils/theme.ts),
// mémorisé sur le profil.

const CHOICES: { value: ThemePreference; label: string; hint: string; icon: string }[] = [
  { value: 'light', label: 'Clair', hint: 'Toujours clair', icon: 'bi-sun' },
  { value: 'dark', label: 'Sombre', hint: 'Toujours sombre', icon: 'bi-moon-stars' },
  { value: 'system', label: 'Système', hint: 'Suit l’appareil', icon: 'bi-circle-half' },
]

export default function AppearanceCard() {
  const { preference, dark, setPreference } = useTheme()
  return (
    <section className="pt-card h-100 st-card" aria-labelledby="st-appearance-title">
      <h2 className="st-card-title" id="st-appearance-title"><i className="bi bi-palette" aria-hidden="true"></i>Apparence</h2>
      <div className="st-choices" role="radiogroup" aria-labelledby="st-appearance-title">
        {CHOICES.map((c) => (
          <label key={c.value} className={`st-choice${preference === c.value ? ' active' : ''}`}>
            <input type="radio" name="st-theme" value={c.value} checked={preference === c.value} onChange={() => setPreference(c.value)} />
            <i className={`bi ${c.icon}`} aria-hidden="true"></i>
            <span className="st-choice-label">{c.label}</span>
            <span className="st-choice-hint">{c.hint}</span>
          </label>
        ))}
      </div>
      <p className="st-note">
        {preference === 'system' ? `Affichage actuel : ${dark ? 'sombre' : 'clair'}, comme votre appareil.` : 'Retrouvé sur vos autres appareils.'}
        {' '}Le bouton de la barre latérale passe directement en clair ou en sombre.
      </p>
    </section>
  )
}
