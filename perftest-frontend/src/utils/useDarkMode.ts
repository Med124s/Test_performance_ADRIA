import { useCallback, useState } from 'react'

// ============================================================
// P1-D — le bascule clair/sombre existait déjà (MainLayout.tsx/Sidebar.tsx)
// et fonctionne réellement (attribut `data-theme` sur <html>, lu par le
// CSS), mais n'était PAS persisté : chaque rechargement de page revenait
// au mode clair, même si l'utilisateur venait de choisir le mode sombre —
// un vrai bug, pas juste un manque de fonctionnalité. Corrigé ici en
// persistant le choix en localStorage (même politique que
// `pt_sidebar_expanded`, une préférence par navigateur, jamais un besoin
// de synchronisation serveur/multi-appareil).
// ============================================================

const STORAGE_KEY = 'pt_dark_mode'

function readInitial(): boolean {
  try {
    return localStorage.getItem(STORAGE_KEY) === '1'
  } catch {
    return false
  }
}

function applyThemeAttribute(dark: boolean) {
  document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light')
}

/** Source UNIQUE de vérité pour le mode sombre — utilisé par MainLayout
 * (bascule réelle dans la Sidebar) et par SettingsIntegrations.tsx
 * (affichage/contrôle du même état, jamais un second état dupliqué). */
export function useDarkMode() {
  const [darkMode, setDarkModeState] = useState<boolean>(() => {
    const initial = readInitial()
    applyThemeAttribute(initial)
    return initial
  })

  const setDarkMode = useCallback((next: boolean) => {
    setDarkModeState(next)
    applyThemeAttribute(next)
    try {
      localStorage.setItem(STORAGE_KEY, next ? '1' : '0')
    } catch {
      // Stockage indisponible (navigation privée...) : le thème reste
      // fonctionnel pour cette session, simplement non persisté.
    }
  }, [])

  const toggleDarkMode = useCallback(() => setDarkMode(!darkMode), [darkMode, setDarkMode])

  return { darkMode, setDarkMode, toggleDarkMode }
}
