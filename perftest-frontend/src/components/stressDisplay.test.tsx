import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import LiveStress from './LiveStress'
import StressResultCard from './StressResultCard'
import { progressOf } from '../context/ExecutionMonitorContext'
import { bucketIndexAt, stressMarks } from '../pages/execution-report/timelineModel'
import { stressOutcomeTitle, stressProgressLabel } from '../utils/stressResult'
import { BackendExecutionStatusResponse, BackendStressLive, BackendStressResult } from '../types/backendContracts'

const nbsp = (s: string | null | undefined) => (s ?? '').replace(/[  ]/g, ' ')

const BREAKPOINT: BackendStressResult = {
  outcome: 'BREAKPOINT', limit: null, message: 'Point de rupture vers 182 req/s offertes et 57 utilisateur(s) virtuel(s), à 3 min 42 s du début.',
  peakVirtualUsers: 64, peakOfferedRps: 197, criteria: ['RESPONSE_TIME', 'THROUGHPUT'], onsetSeconds: 222, detectedSeconds: 237,
  virtualUsers: 57, offeredRps: 182, throughputRps: 171.5, p95Ms: 1240, baselineP95Ms: 300, refusalPercent: 1.2,
}

const LIVE: BackendStressLive = {
  phase: 'RAMP', elapsedSeconds: 95, warmupEndSeconds: 10, baselineEndSeconds: 40, offeredRps: 57, peakOfferedRps: 57, virtualUsers: 12,
  baselineP95Ms: 85, holdingLimit: null, maxVirtualUsers: 200, maxRps: 500, maxDurationSeconds: 3600,
}

describe('Mode stress — affichage du résultat et de la montée', () => {
  it('titres du résultat', () => {
    expect(nbsp(stressOutcomeTitle(BREAKPOINT))).toBe('Point de rupture vers 182 req/s')
    expect(nbsp(stressOutcomeTitle({ ...BREAKPOINT, outcome: 'NO_BREAKPOINT', limit: 'TARGET_RPS', peakOfferedRps: 500 }))).toBe('Aucune rupture jusqu’à 500 req/s')
    expect(stressOutcomeTitle({ ...BREAKPOINT, outcome: 'BASELINE_UNUSABLE' })).toBe('Mesure de référence impossible')
    expect(stressOutcomeTitle({ ...BREAKPOINT, outcome: 'INCONCLUSIVE' })).toBe('Non concluant : LoadPilot saturé')
    expect(stressOutcomeTitle({ ...BREAKPOINT, outcome: null })).toBe('Test de stress interrompu')
  })

  it('progression : phase de la montée au lieu d’un pourcentage', () => {
    expect(nbsp(stressProgressLabel(LIVE))).toBe('Montée · 57 req/s offertes')
    expect(stressProgressLabel({ ...LIVE, phase: 'BASELINE', elapsedSeconds: 25 })).toBe('Mesure de référence · 15 s restantes')
    expect(nbsp(stressProgressLabel({ ...LIVE, phase: 'HOLD', holdingLimit: 'TARGET_RPS', offeredRps: 500 })))
      .toBe('Limite atteinte (débit cible maximal) · 500 req/s offertes')
    const status = { status: 'RUNNING', progressPercent: null, live: { startedVirtualUsers: 3, activeVirtualUsers: 3 }, stressLive: LIVE } as unknown as BackendExecutionStatusResponse
    expect(progressOf(status).percent).toBeNull()
    expect(nbsp(progressOf(status).label)).toBe('Montée · 57 req/s offertes')
  })

  it('repères du graphique : intervalle de la mesure de référence, début et confirmation de la rupture', () => {
    const t = [0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 220, 225, 230, 235, 240]
    expect(bucketIndexAt(t, -1)).toBe(-1)
    expect(bucketIndexAt(t, 12)).toBe(2)
    expect(bucketIndexAt(t, 999)).toBe(14)
    expect(stressMarks(t, BREAKPOINT, 10, 40)).toEqual({ baseline: { from: 2, to: 7 }, onset: 10, confirmed: 13 })
    expect(stressMarks(t, { ...BREAKPOINT, outcome: 'NO_BREAKPOINT' }, 10, 40)).toEqual({ baseline: { from: 2, to: 7 }, onset: null, confirmed: null })
  })

  it('suivi en direct : phase courante, débit offert, utilisateurs et référence', () => {
    render(<LiveStress live={LIVE} />)
    const card = screen.getByTestId('live-stress')
    expect(screen.getByText('Montée').getAttribute('aria-current')).toBe('step')
    expect(nbsp(card.textContent)).toContain('Débit offert 57 req/s')
    expect(card.textContent).toContain('Utilisateurs virtuels 12')
    expect(nbsp(card.textContent)).toContain('P95 de référence 85 ms')
  })

  it('résultat : niveau de charge au début de l’anomalie, ou montée jusqu’à la limite', () => {
    const { unmount } = render(<StressResultCard stress={BREAKPOINT} />)
    const card = screen.getByTestId('stress-result')
    expect(card.textContent).toContain('temps de réponse (P95) ; débit obtenu sous le débit offert')
    expect(card.textContent).toContain('à 3 min 42 s (confirmée à 3 min 57 s)')
    expect(nbsp(card.textContent)).toContain('57 utilisateur(s) virtuel(s), 182 req/s offertes')
    expect(nbsp(card.textContent)).toContain('1 240 ms')
    unmount()

    render(<StressResultCard stress={{ ...BREAKPOINT, outcome: 'NO_BREAKPOINT', limit: 'TARGET_RPS', peakVirtualUsers: 120, peakOfferedRps: 500, message: null }} />)
    expect(nbsp(screen.getByTestId('stress-result').textContent))
      .toContain('Montée jusqu’à 120 utilisateur(s) virtuel(s) et 500 req/s offertes, arrêtée à la limite du serveur (débit cible maximal).')
  })

  it('vitesse de montée : dans le suivi en direct, et dans le résultat avec la précision du point de rupture', () => {
    const { unmount } = render(<LiveStress live={{ ...LIVE, rampRpsPerSecond: 20 }} />)
    expect(nbsp(screen.getByTestId('live-stress').textContent)).toContain('Montée +20 req/s par seconde')
    unmount()

    const second = render(<StressResultCard stress={{ ...BREAKPOINT, rampRpsPerSecond: 20 }} />)
    expect(nbsp(screen.getByTestId('stress-result').textContent))
      .toContain('Vitesse de montée+20 req/s par seconde · rupture située à ± 100 req/s près')
    second.unmount()

    render(<StressResultCard stress={{ ...BREAKPOINT, outcome: 'NO_BREAKPOINT', limit: 'TARGET_RPS', peakVirtualUsers: 120, peakOfferedRps: 500, message: null, rampRpsPerSecond: 20 }} />)
    expect(nbsp(screen.getByTestId('stress-result').textContent))
      .toContain('500 req/s offertes (+20 req/s par seconde), arrêtée à la limite du serveur')
  })
})
