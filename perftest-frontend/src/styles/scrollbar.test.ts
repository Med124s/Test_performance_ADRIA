import { describe, expect, it } from 'vitest'

// Barre de défilement personnalisée (global.css) : couleurs du thème uniquement, réglages Firefox
// réservés aux navigateurs sans ::-webkit-scrollbar (sinon Chrome ignorerait la forme arrondie).
// Le rendu lui-même est vérifié dans un vrai navigateur (jsdom n'affiche pas de barre de défilement).
// Fichiers lus sur le disque : les imports .css sont vides dans les tests. Les types Node ne sont pas
// chargés par le tsconfig du frontend : seules les deux fonctions utilisées sont décrites.

interface NodeFs {
  readdirSync(path: string, options: { recursive: true }): string[]
  readFileSync(path: string, encoding: 'utf8'): string
}
const fs = (await import(/* @vite-ignore */ 'node:' + 'fs')) as NodeFs
const SRC = (globalThis as unknown as { process: { cwd(): string } }).process.cwd() + '/src'
const allCss: [string, string][] = fs.readdirSync(SRC, { recursive: true })
  .filter((f) => f.endsWith('.css'))
  .map((f) => [f.replace(/\\/g, '/'), fs.readFileSync(`${SRC}/${f}`, 'utf8').replace(/\r\n/g, '\n')])
const globalCss = allCss.find(([f]) => f === 'styles/global.css')![1]
const block = globalCss.slice(globalCss.indexOf('Barre de défilement'), globalCss.indexOf('/* Responsive */'))

describe('Barre de défilement', () => {
  it('fine et arrondie, couleurs tirées des variables du thème (aucune couleur codée en dur)', () => {
    expect(block).toMatch(/::-webkit-scrollbar \{\s*width: 10px;\s*height: 10px;/)
    expect(block).toMatch(/::-webkit-scrollbar-thumb \{[^}]*border-radius: 999px;/)
    expect(block).toMatch(/::-webkit-scrollbar-thumb:hover \{\s*background-color: var\(--pt-scrollbar-thumb-hover\);/)
    const code = block.replace(/\/\*[\s\S]*?\*\//g, '')
    expect(code).not.toMatch(/#[0-9a-f]{3,8}\b|rgba?\(|hsla?\(/i)
    for (const [, value] of code.matchAll(/--pt-scrollbar-[a-z-]+:\s*([^;]+);/g)) {
      expect(value).toMatch(/var\(--pt-(border|text-light|text-muted)\)/)
    }
  })

  it('Firefox : scrollbar-width / scrollbar-color seulement sans ::-webkit-scrollbar', () => {
    const firefox = block.slice(block.indexOf('@supports not selector(::-webkit-scrollbar)'))
    expect(firefox).toMatch(/scrollbar-width: thin;/)
    expect(firefox).toMatch(/scrollbar-color: var\(--pt-scrollbar-thumb\) transparent;/)
    // Ailleurs, seul « none » (barre volontairement masquée) est permis : « thin » désactiverait le style dans Chrome.
    for (const [file, css] of allCss) {
      const outside = file.endsWith('global.css') ? css.replace(firefox, '') : css
      expect(outside.match(/scrollbar-(width|color):\s*(?!none)\S[^;]*/g) ?? [], file).toEqual([])
      if (!file.endsWith('global.css')) expect(css, file).not.toMatch(/::-webkit-scrollbar/)
    }
  })
})
