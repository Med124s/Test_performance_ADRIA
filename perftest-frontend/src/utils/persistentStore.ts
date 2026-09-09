// Aide générique de stockage persistant (localStorage), utilisée par les
// différents "stores" de l'application (scénarios, applications,
// exécutions, configurations, notifications...) pour que toute donnée
// enregistrée par l'utilisateur survive à un changement de page ou à un
// rechargement complet de l'application.

export function loadJSON<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key)
    if (!raw) return fallback
    return JSON.parse(raw) as T
  } catch {
    return fallback
  }
}

export function saveJSON<T>(key: string, value: T): void {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    // Stockage indisponible (navigation privée, quota dépassé...) : on
    // ignore silencieusement plutôt que de casser l'interface.
  }
}
