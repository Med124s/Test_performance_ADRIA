import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeAll, describe, expect, it, vi } from 'vitest'

// Feuilles de style réelles, lues sur le disque (sous Vitest, un import « ?raw » de CSS est vide). Les types
// Node ne sont pas chargés par le tsconfig du frontend : seule la fonction utilisée est décrite.
const fs = (await import(/* @vite-ignore */ 'node:' + 'fs')) as { readFileSync(path: string, encoding: 'utf8'): string }
const SRC = (globalThis as unknown as { process: { cwd(): string } }).process.cwd() + '/src'
const settingsCss = fs.readFileSync(`${SRC}/pages/settings/settings.css`, 'utf8')
const globalCss = fs.readFileSync(`${SRC}/styles/global.css`, 'utf8')

// Ce qui est AFFICHÉ sous chaque champ de « Limites » (texte exact, couleur effective), pas une classe ni un
// état interne : régressions visuelles détectées (aide associée au mauvais champ, erreur affichée en gris).

const settingsApi = vi.hoisted(() => ({ limits: vi.fn(), updateLimits: vi.fn() }))
vi.mock('../../services/api/settingsBackend', () => ({ settingsBackendApi: settingsApi }))
vi.mock('../../context/ToastContext', () => ({ useToast: () => ({ showToast: vi.fn() }) }))

import LimitsCard from './LimitsCard'

const RESPONSE = {
  limits: [
    { key: 'virtualUsersPerTest', label: 'Utilisateurs virtuels par test', unit: 'utilisateurs', value: 200, ceiling: 200 },
    { key: 'virtualUsersTotal', label: 'Utilisateurs virtuels au total', unit: 'utilisateurs', value: 500, ceiling: 500 },
    { key: 'concurrentTests', label: 'Tests en même temps', unit: 'tests', value: 10, ceiling: 10 },
    { key: 'maxDurationSeconds', label: 'Durée maximale d’un test', unit: 's', value: 3600, ceiling: 3600 },
    { key: 'maxIterations', label: 'Itérations maximales par utilisateur', unit: 'itérations', value: 10000, ceiling: 10000 },
    { key: 'maxTargetRps', label: 'Débit cible maximal', unit: 'req/s', value: 500, ceiling: 500 },
  ],
  executionTimeoutSeconds: 10, availabilityTimeoutSeconds: 5, effectiveVirtualUsersPerTest: 200, notices: [],
}

/** [libellé, texte affiché sous le champ, valeur] pour chaque champ, dans l'ordre de l'écran. */
function displayed(card: HTMLElement): [string, string, string][] {
  return within(card).getAllByRole('textbox').map((input) => {
    const field = input.closest('.st-field') as HTMLElement
    return [field.querySelector('strong')!.firstChild!.textContent!.trim(), field.querySelector('label > span')!.textContent!.trim(),
      (input as HTMLInputElement).value]
  })
}

async function renderCard() {
  settingsApi.limits.mockResolvedValue(RESPONSE)
  render(<LimitsCard />)
  const card = (await screen.findByRole('heading', { name: 'Limites' })).closest('section') as HTMLElement
  await waitFor(() => expect(within(card).getAllByRole('textbox')).toHaveLength(6))
  return card
}

describe('Limites : texte exact sous chaque champ', () => {
  it('chaque champ affiche SON plafond, « au total » en premier', async () => {
    const card = await renderCard()
    expect(displayed(card)).toEqual([
      ['Utilisateurs virtuels au total', 'Maximum 500 (plafond du serveur).', '500'],
      ['Utilisateurs virtuels par test', 'Maximum 200 (plafond du serveur).', '200'],
      ['Tests en même temps', 'Maximum 10 (plafond du serveur).', '10'],
      ['Durée maximale d’un test', 'Maximum 3 600 (plafond du serveur).', '3600'],
      ['Itérations maximales par utilisateur', 'Maximum 10 000 (plafond du serveur).', '10000'],
      ['Débit cible maximal', 'Maximum 500 (plafond du serveur).', '500'],
    ])
  })

  it('total sous « par test » : le message d’erreur remplace l’aide sous les DEUX champs, et seulement eux', async () => {
    const card = await renderCard()
    fireEvent.change(within(card).getByRole('textbox', { name: /Utilisateurs virtuels au total/ }), { target: { value: '150' } })
    expect(displayed(card).slice(0, 3)).toEqual([
      ['Utilisateurs virtuels au total', 'Le total doit être supérieur ou égal au maximum par test.', '150'],
      ['Utilisateurs virtuels par test', 'Le total doit être supérieur ou égal au maximum par test.', '200'],
      ['Tests en même temps', 'Maximum 10 (plafond du serveur).', '10'],
    ])
    expect((within(card).getByRole('button', { name: 'Enregistrer' }) as HTMLButtonElement).disabled).toBe(true)
    // Corrigé : l'aide revient sous chaque champ, avec le bon plafond.
    fireEvent.change(within(card).getByRole('textbox', { name: /Utilisateurs virtuels par test/ }), { target: { value: '150' } })
    expect(displayed(card).slice(0, 2)).toEqual([
      ['Utilisateurs virtuels au total', 'Maximum 500 (plafond du serveur).', '150'],
      ['Utilisateurs virtuels par test', 'Maximum 200 (plafond du serveur).', '150'],
    ])
    expect((within(card).getByRole('button', { name: 'Enregistrer' }) as HTMLButtonElement).disabled).toBe(false)
  })
})

/** Couleurs du thème clair (global.css, :root) : jsdom ne résout pas var(), elles sont donc substituées. */
function themeVariables(): Record<string, string> {
  const root = globalCss.slice(globalCss.indexOf(':root'), globalCss.indexOf('}', globalCss.indexOf(':root')))
  return Object.fromEntries([...root.matchAll(/(--pt-[\w-]+)\s*:\s*([^;]+);/g)].map((m) => [m[1], m[2].trim()]))
}

const hexToRgb = (hex: string) => {
  const n = parseInt(hex.replace('#', ''), 16)
  return `rgb(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255})`
}

/** Spécificité CSS [id, classes/attributs/pseudo-classes, éléments] d'un sélecteur simple (ceux de settings.css). */
function specificity(selector: string): [number, number, number] {
  const s = selector.replace(/::[\w-]+/g, ' ')
  const ids = (s.match(/#[\w-]+/g) ?? []).length
  const classes = (s.match(/\.[\w-]+|\[[^\]]+\]|:(?!:)[\w-]+(\([^)]*\))?/g) ?? []).length
  const elements = (s.replace(/#[\w-]+|\.[\w-]+|\[[^\]]+\]|:[\w-]+(\([^)]*\))?/g, ' ').match(/(^|[\s>+~])([a-z][\w-]*)/gi) ?? []).length
  return [ids, classes, elements]
}

/**
 * Valeur gagnante d'une propriété pour un élément, calculée comme un navigateur : règles qui s'appliquent
 * (element.matches), la plus spécifique l'emporte, à égalité la dernière. jsdom ne gère pas la spécificité
 * dans getComputedStyle (il garde la dernière règle) : sans ce calcul, l'erreur grise ne serait pas vue.
 */
function cascadedValue(el: Element, property: string): string | null {
  let best: { spec: [number, number, number]; order: number; value: string } | null = null
  let order = 0
  for (const sheet of Array.from(document.styleSheets)) {
    for (const rule of Array.from(sheet.cssRules)) {
      if (!(rule instanceof CSSStyleRule)) continue
      order++
      const value = rule.style.getPropertyValue(property)
      if (!value) continue
      for (const selector of rule.selectorText.split(',').map((x) => x.trim())) {
        let matches = false
        try { matches = el.matches(selector) } catch { matches = false }
        if (!matches) continue
        const spec = specificity(selector)
        const wins = !best || spec[0] !== best.spec[0] ? !best || spec[0] > best.spec[0]
          : spec[1] !== best.spec[1] ? spec[1] > best.spec[1]
          : spec[2] !== best.spec[2] ? spec[2] > best.spec[2] : order >= best.order
        if (wins) best = { spec, order, value }
      }
    }
  }
  return best?.value ?? null
}

describe('Limites : couleur effective (feuille de style réelle settings.css, cascade et spécificité)', () => {
  const vars = themeVariables()
  beforeAll(() => {
    const style = document.createElement('style')
    style.textContent = settingsCss.replace(/var\((--pt-[\w-]+)\)/g, (_: string, name: string) => vars[name] ?? 'inherit')
    document.head.appendChild(style)
  })

  it('l’erreur sous un champ n’hérite jamais du gris de l’aide : couleur « danger », bordure du champ en erreur', async () => {
    const danger = hexToRgb(vars['--pt-danger'])
    const muted = hexToRgb(vars['--pt-text-muted'])
    const card = await renderCard()
    const color = (el: Element) => { const v = cascadedValue(el, 'color'); return v?.startsWith('#') ? hexToRgb(v) : v }
    const border = (el: Element) => { const v = cascadedValue(el, 'border-color'); return v?.startsWith('#') ? hexToRgb(v) : v }
    expect(color(card.querySelector('.st-field label > span')!), 'aide : gris').toBe(muted)
    fireEvent.change(within(card).getByRole('textbox', { name: /Utilisateurs virtuels au total/ }), { target: { value: '150' } })
    for (const label of [/Utilisateurs virtuels au total/, /Utilisateurs virtuels par test/]) {
      const input = within(card).getByRole('textbox', { name: label })
      const message = (input.closest('.st-field') as HTMLElement).querySelector('label > span') as HTMLElement
      expect(message.textContent).toBe('Le total doit être supérieur ou égal au maximum par test.')
      expect(color(message), 'texte de l’erreur : rouge, pas le gris de l’aide').toBe(danger)
      expect(border(input), 'bordure du champ').toBe(danger)
    }
    // Les autres champs gardent leur aide grise.
    const other = within(card).getByRole('textbox', { name: /Tests en même temps/ }).closest('.st-field')!.querySelector('label > span')!
    expect(color(other)).toBe(muted)
  })
})
