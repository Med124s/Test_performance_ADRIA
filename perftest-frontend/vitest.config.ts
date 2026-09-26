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
  },
})
