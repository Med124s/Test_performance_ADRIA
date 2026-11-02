import { useCallback } from 'react'
import { useTheme } from './theme'

// Bouton clair / sombre de la barre latérale, branché sur l'apparence partagée (utils/theme.ts).

export function useDarkMode() {
  const { dark, setPreference } = useTheme()
  const setDarkMode = useCallback((next: boolean) => setPreference(next ? 'dark' : 'light'), [setPreference])
  const toggleDarkMode = useCallback(() => setPreference(dark ? 'light' : 'dark'), [dark, setPreference])
  return { darkMode: dark, setDarkMode, toggleDarkMode }
}
