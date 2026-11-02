// « Mon compte » : libellés des rôles et validation de l'identité modifiable.

import { appRoleFromRawRoles } from '../../utils/appRoles'

/** Libellé français du rôle. */
export const ACCOUNT_ROLE_LABEL = {
  SUPER_ADMIN: 'Super administrateur',
  PERFORMANCE_ENGINEER: 'Ingénieur performance',
  VIEWER: 'Observateur',
} as const

export function accountRoleLabel(rawRoles: string[]): string {
  const role = appRoleFromRawRoles(rawRoles)
  return role ? ACCOUNT_ROLE_LABEL[role] : 'Aucun rôle'
}

export interface IdentityValues {
  firstName: string
  lastName: string
  email: string
}

export type IdentityErrors = Partial<Record<keyof IdentityValues, string>>

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

/** Erreurs par champ, avec les messages du serveur ; objet vide si tout est valide. */
export function validateIdentity(v: IdentityValues): IdentityErrors {
  const errors: IdentityErrors = {}
  if (!v.firstName.trim()) errors.firstName = 'Le prénom est obligatoire.'
  else if (v.firstName.trim().length > 100) errors.firstName = 'Le prénom ne doit pas dépasser 100 caractères.'
  if (!v.lastName.trim()) errors.lastName = 'Le nom est obligatoire.'
  else if (v.lastName.trim().length > 100) errors.lastName = 'Le nom ne doit pas dépasser 100 caractères.'
  if (!v.email.trim()) errors.email = 'L’e-mail est obligatoire.'
  else if (!EMAIL.test(v.email.trim())) errors.email = 'L’e-mail n’est pas valide.'
  else if (v.email.trim().length > 254) errors.email = 'L’e-mail ne doit pas dépasser 254 caractères.'
  return errors
}

/** Fuseau du navigateur, utilisé quand l'utilisateur n'en a pas choisi. */
export { browserTimezone } from '../../utils/userTimezone'
