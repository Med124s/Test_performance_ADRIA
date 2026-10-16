// P1-Q Étape B — matchers DOM (toBeInTheDocument, toBeDisabled, ...) pour
// les tests de composants (voir vitest.config.ts, test.setupFiles).
import '@testing-library/jest-dom'
import { afterEach } from 'vitest'
import { setUserTimezone } from './utils/userTimezone'

// Le fuseau de Mon profil (simulé par un test) ne doit pas déborder sur le test suivant :
// chaque test repart du fuseau du navigateur.
afterEach(() => setUserTimezone(null))
