// Matchers DOM (toBeInTheDocument, toBeDisabled...) pour les tests de composants.
import '@testing-library/jest-dom'
import { afterEach } from 'vitest'
import { setUserTimezone } from './utils/userTimezone'

// Chaque test repart du fuseau du navigateur.
afterEach(() => setUserTimezone(null))
