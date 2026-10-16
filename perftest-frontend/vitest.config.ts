import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// Fichier séparé de vite.config.ts (jamais modifié) : évite tout risque sur
// la configuration de dev/build existante pour n'ajouter que la config de
// test (P1-Q Étape B — introduction du framework de test frontend).
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/setupTests.ts'],
    globals: true,
    // Plusieurs tests d'écran complets prennent ~2 s seuls mais dépassent 5 s quand toute la
    // suite tourne en parallèle sur une machine chargée : 15 s évite ces échecs aléatoires.
    testTimeout: 15_000,
    // Au plus 6 fichiers de test en parallèle : au-delà, les tests d'écran se disputent le
    // processeur (un test de 2 s dépassait 15 s) sans que la suite aille plus vite.
    poolOptions: { forks: { maxForks: 6, minForks: 1 } },
  },
})
