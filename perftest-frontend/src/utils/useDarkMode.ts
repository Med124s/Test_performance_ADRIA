import { useCallback } from 'react'
import { useTheme } from './theme'

// ============================================================
// Bouton clair / sombre de la barre latérale : simple vue sur l'état PARTAGÉ de l'apparence
// (voir utils/theme.ts) — un changement fait dans Paramètres se voit aussitôt ici, et
// inversement. Le bouton impose « clair » ou « sombre » (il quitte donc « Système »).
// ============================================================

export function useDarkMode() {
  const { dark, setPreference } = useTheme()
  const setDarkMode = useCallback((next: boolean) => setPreference(next ? 'dark' : 'light'), [setPreference])
  const toggleDarkMode = useCallback(() => setPreference(dark ? 'light' : 'dark'), [dark, setPreference])
  return { darkMode: dark, setDarkMode, toggleDarkMode }
}
