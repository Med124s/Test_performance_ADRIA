import { useEffect, useRef } from 'react'
import { scenariosApi } from '../services/api/scenarios'
import { useScenarioLauncher, LaunchForm } from './useScenarioLauncher'
import { loadDefaultTestSettings } from '../utils/defaultTestSettings'

const CHECK_INTERVAL_MS = 15000

/** Calcule la prochaine occurrence d'un scénario Récurrent à partir de sa
 * dernière échéance déclenchée et de sa fréquence ("Quotidien" par défaut
 * pour toute valeur inconnue/absente — même repli que le `<select>` de
 * CreateStep.tsx, qui liste "Quotidien" en premier). */
function computeNextOccurrence(from: Date, recurrence: string | undefined): Date {
  const next = new Date(from)
  switch (recurrence) {
    case 'Hebdomadaire':
      next.setDate(next.getDate() + 7)
      break
    case 'Mensuel':
      next.setMonth(next.getMonth() + 1)
      break
    default:
      next.setDate(next.getDate() + 1)
  }
  return next
}

/**
 * Déclencheur réel des scénarios Planifiés ET Récurrents ("Planification" —
 * Type d'exécution "Planifiée"/"Récurrente", voir CreateStep.tsx écran 5).
 * Monté UNE SEULE FOIS dans MainLayout (donc actif sur toutes les pages
 * authentifiées) : vérifie périodiquement si un scénario a une échéance déjà
 * passée, et le lance alors pour de vrai (vraies requêtes HTTP, comme un
 * lancement manuel) — invisible (aucune modale), mais une vraie Execution
 * est bien créée et écrite sur JSON Server.
 *
 * Planifiée : se déclenche une seule fois à la date/heure choisie, puis
 * repasse en "immediate" pour ne plus jamais se relancer toute seule.
 * Récurrente : se déclenche dès que la prochaine échéance est atteinte
 * (immédiatement lors de la toute première détection si aucune échéance
 * n'est encore enregistrée — un scénario Récurrent démarre donc dès sa
 * création), puis se reprogramme elle-même pour l'occurrence suivante selon
 * sa fréquence (Quotidien/Hebdomadaire/Mensuel) au lieu de s'arrêter.
 *
 * Limite honnête, inhérente à une architecture 100% frontend sans serveur
 * dédié : ça ne fonctionne que tant qu'un onglet de l'application reste
 * ouvert dans le navigateur. Fermer l'onglet avant l'échéance fait manquer
 * le déclenchement (voir CONCEPTION_GENERALE.md, section Architecture
 * actuelle).
 */
export function useScheduledExecutions() {
  const launcher = useScenarioLauncher()
  // Empêche de relancer deux fois le même scénario pendant l'intervalle où
  // le PATCH qui efface sa planification n'est pas encore reflété par un
  // prochain fetch (la même précaution existe pour l'anti double-clic
  // ailleurs dans le projet).
  const firingRef = useRef<Set<string>>(new Set())

  useEffect(() => {
    let cancelled = false

    const check = async () => {
      let scenarios
      try {
        scenarios = await scenariosApi.getAll()
      } catch {
        return
      }
      if (cancelled) return

      const now = Date.now()
      for (const sc of scenarios) {
        const executionType = sc.schedule?.executionType
        if (executionType !== 'scheduled' && executionType !== 'recurring') continue
        if (firingRef.current.has(sc.id)) continue

        const hasDate = !!sc.schedule?.scheduledDate
        let due: Date
        if (hasDate) {
          due = new Date(`${sc.schedule!.scheduledDate}T${sc.schedule!.scheduledTime || '00:00'}`)
          if (Number.isNaN(due.getTime())) continue
        } else if (executionType === 'recurring') {
          // Récurrente sans échéance encore enregistrée : première
          // occurrence dès cette détection (aucun sélecteur de date de
          // départ n'existe pour "Récurrente" dans le wizard).
          due = new Date(now)
        } else {
          continue // "Planifiée" sans date : rien à faire.
        }
        if (due.getTime() > now) continue

        firingRef.current.add(sc.id)
        try {
          if (executionType === 'scheduled') {
            // Efface la planification AVANT de lancer : une exécution
            // Planifiée déjà déclenchée ne doit plus jamais se relancer
            // toute seule (ni au prochain contrôle, ni après un rechargement
            // de page).
            await scenariosApi.update(sc.id, {
              schedule: { ...sc.schedule, executionType: 'immediate' },
            })
          } else {
            // Récurrente : reprogrammée pour sa prochaine occurrence au lieu
            // d'être désactivée — c'est ce qui la fait vraiment "répéter".
            const next = computeNextOccurrence(due, sc.schedule?.recurrence)
            await scenariosApi.update(sc.id, {
              schedule: {
                ...sc.schedule,
                executionType: 'recurring',
                scheduledDate: next.toISOString().slice(0, 10),
                scheduledTime: `${String(next.getHours()).padStart(2, '0')}:${String(next.getMinutes()).padStart(2, '0')}`,
              },
            })
          }

          const defaults = loadDefaultTestSettings()
          const formOverride: LaunchForm = {
            scenarioId: sc.id,
            applicationId: sc.applicationId,
            virtualUsers: sc.virtualUsers ?? defaults.concurrency,
            duration: 300,
            rampUp: sc.rampUpSeconds ?? defaults.rampUpSeconds,
            thinkTime: defaults.stepDelayMs,
            debit: '',
            stopMode: 'auto',
          }
          await launcher.handleLaunch(sc, formOverride)
        } catch {
          // Échec du déclenchement (application/étapes introuvables...) :
          // on retire le verrou pour retenter au prochain contrôle plutôt
          // que d'abandonner silencieusement pour toujours.
          firingRef.current.delete(sc.id)
          continue
        }
        // Récurrente : la prochaine échéance est désormais dans le futur
        // (voir mise à jour ci-dessus) — le verrou n'est donc plus utile
        // pour CETTE occurrence ; le retirer permet au prochain contrôle de
        // détecter normalement l'occurrence suivante le moment venu.
        if (executionType === 'recurring') firingRef.current.delete(sc.id)
      }
    }

    check()
    const interval = setInterval(check, CHECK_INTERVAL_MS)
    return () => {
      cancelled = true
      clearInterval(interval)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
}
