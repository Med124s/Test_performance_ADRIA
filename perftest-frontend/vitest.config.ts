import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// Configuration des tests (Vitest, jsdom).
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/setupTests.ts'],
    globals: true,
    // Délai d'un test : 15 s (tests d'écran lancés en parallèle).
    testTimeout: 15_000,
    // Au plus 6 fichiers de test en parallèle.
    poolOptions: { forks: { maxForks: 6, minForks: 1 } },
  },
})
