import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import TopBar from '../components/TopBar'
import { useAuth } from '../context/AuthContext'
import { usersBackendApi } from '../services/api/usersBackend'
import { BackendAppRole, BackendUserSummaryResponse } from '../types/backendContracts'
import { ApiError } from '../services/api/httpClient'

// ============================================================
// P1-D — REMPLACE ENTIÈREMENT l'ancienne page (voir rapport P1-D, section
// "Roles Catalog", pour l'analyse complète). L'ancienne version gérait un
// CRUD de "rôles" arbitraires sur JSON Server (/roles), totalement
// déconnecté de l'autorisation réelle (Spring Security ne connaît QUE les
// 3 rôles Keycloak ci-dessous — créer/modifier/supprimer une entrée
// JSON-Server ne change RIEN aux permissions réelles), plus une
// attribution rôle↔utilisateur 100% fictive en localStorage (8 faux
// utilisateurs codés en dur, aucun rapport avec les vrais comptes
// Keycloak déjà gérés par UsersRoles.tsx).
//
// Cette page est désormais une représentation HONNÊTE, en LECTURE SEULE,
// des 3 rôles applicatifs réellement supportés par LoadPilot (voir
// SecurityConfig/@PreAuthorize sur chaque contrôleur backend) : ce que
// chaque rôle peut réellement faire (matrice reflétant le code, pas une
// donnée mutable) et, pour un Super Administrateur, le nombre RÉEL
// d'utilisateurs Keycloak par rôle (réutilise GET /api/users, déjà réel -
// voir UsersRoles.tsx - jamais une seconde source de vérité).
//
// Aucun CRUD de rôle n'existe ici : gérer QUI a quel rôle se fait
// exclusivement sur /users-roles (Keycloak réel). `services/api/roles.ts`
// (JSON Server) n'est plus importé par aucune page après ce changement -
// laissé en place (fichier mort, faible risque) plutôt que supprimé, voir
// le rapport P1-D pour la justification de ne pas le retirer dans cette
// phase.
// ============================================================

const ROLES: BackendAppRole[] = ['SUPER_ADMIN', 'PERFORMANCE_ENGINEER', 'VIEWER']

const roleBadgeStyle: Record<BackendAppRole, { bg: string; color: string }> = {
  SUPER_ADMIN: { bg: 'var(--pt-primary-light)', color: 'var(--pt-primary)' },
  PERFORMANCE_ENGINEER: { bg: 'var(--pt-success-light)', color: 'var(--pt-success)' },
  VIEWER: { bg: 'var(--pt-warning-light)', color: 'var(--pt-warning)' },
}

const roleLabel: Record<BackendAppRole, string> = {
  SUPER_ADMIN: 'Super Administrateur',
  PERFORMANCE_ENGINEER: 'Ingénieur Performance',
  VIEWER: 'Observateur',
}

const roleDescription: Record<BackendAppRole, string> = {
  SUPER_ADMIN: "Accès complet : toutes les capacités de l'Ingénieur Performance, plus l'administration des utilisateurs/rôles et la consultation des Audit Logs.",
  PERFORMANCE_ENGINEER: 'Peut créer/modifier des Applications, Scénarios, Étapes et Planifications, lancer/annuler/relancer des Exécutions.',
  VIEWER: "Accès en lecture seule : consulter Applications, Scénarios, Exécutions, Historique, Rapports, Métriques, Dashboard et Planifications.",
}

/** Reflète EXACTEMENT les annotations @PreAuthorize réellement présentes
 * dans le backend (vérifié dans le code, jamais supposé) - à tenir à jour
 * manuellement si la matrice change, comme toute documentation de
 * référence sur une structure de code (pas une donnée mutable). */
const capabilities: { label: string; roles: BackendAppRole[] }[] = [
  { label: 'Consulter Applications / Scénarios / Étapes / Exécutions / Historique / Rapports / Métriques / Dashboard', roles: ['VIEWER', 'PERFORMANCE_ENGINEER', 'SUPER_ADMIN'] },
  { label: 'Consulter mes notifications et planifications', roles: ['VIEWER', 'PERFORMANCE_ENGINEER', 'SUPER_ADMIN'] },
  { label: 'Créer/modifier/supprimer Applications, Scénarios, Étapes', roles: ['PERFORMANCE_ENGINEER', 'SUPER_ADMIN'] },
  { label: 'Lancer / annuler / relancer une Exécution', roles: ['PERFORMANCE_ENGINEER', 'SUPER_ADMIN'] },
  { label: 'Créer/modifier/activer/désactiver/déclencher une Planification', roles: ['PERFORMANCE_ENGINEER', 'SUPER_ADMIN'] },
  { label: 'Consulter les Audit Logs', roles: ['PERFORMANCE_ENGINEER', 'SUPER_ADMIN'] },
  { label: 'Administrer les utilisateurs et leurs rôles (Keycloak)', roles: ['SUPER_ADMIN'] },
  { label: 'Consulter la configuration système', roles: ['SUPER_ADMIN'] },
]

function describeApiError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    switch (err.status) {
      case 0: return err.message
      case 401: return 'Vous devez être connecté (Keycloak) pour consulter les rôles.'
      default: return err.status >= 500 ? 'Erreur du serveur LoadPilot. Réessayez plus tard.' : err.message
    }
  }
  return err instanceof Error ? err.message : fallback
}

function RolesCatalog() {
  const { authProvider, rawRoles } = useAuth()
  const isKeycloak = authProvider === 'keycloak'
  const isSuperAdmin = isKeycloak && rawRoles.includes('ROLE_SUPER_ADMIN')

  const [users, setUsers] = useState<BackendUserSummaryResponse[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!isSuperAdmin) return
    // GET /api/users est reserve SUPER_ADMIN (voir UserController) - deja
    // reel, deja utilise par UsersRoles.tsx : reutilise ici uniquement pour
    // compter les utilisateurs par role, jamais une seconde source de verite.
    usersBackendApi.list()
      .then(setUsers)
      .catch((err) => {
        // 403 theorique (le role a pu changer depuis le rendu initial) :
        // degrade silencieusement (compte simplement non affiche), jamais
        // une bannière d'erreur pour un utilisateur qui n'a de toute facon
        // pas le droit de voir ce decompte.
        if (err instanceof ApiError && err.status === 403) return
        setError(describeApiError(err, "Impossible de charger le nombre d'utilisateurs par rôle."))
      })
  }, [isSuperAdmin])

  const countByRole = useMemo(() => {
    const counts: Record<BackendAppRole, number> = { SUPER_ADMIN: 0, PERFORMANCE_ENGINEER: 0, VIEWER: 0 }
    users?.forEach((u) => { if (u.role) counts[u.role]++ })
    return counts
  }, [users])

  return (
    <div className="pt-content">
      <div className="pt-page-header">
        <div className="page-title">
          <h1>Catalogue des rôles</h1>
          <p>Les 3 rôles applicatifs réels de LoadPilot (Keycloak) et ce que chacun peut réellement faire</p>
        </div>
        <TopBar searchPlaceholder="" />
      </div>

      <div className="pt-card mb-3" style={{ padding: '0.85rem 1.1rem', fontSize: '12.5px', color: 'var(--pt-text-muted)' }}>
        <i className="bi bi-info-circle me-2 text-primary"></i>
        Keycloak est l'unique source de vérité des rôles — cette page est une référence en lecture seule.
        Pour attribuer un rôle à un utilisateur, rendez-vous sur <Link to="/users-roles">Users &amp; Roles</Link>.
      </div>

      {error && (
        <div className="pt-alert-banner danger mb-3">
          <i className="bi bi-exclamation-triangle-fill"></i>
          {error}
        </div>
      )}

      <div className="row g-3 mb-4">
        {ROLES.map((role) => (
          <div className="col-12 col-md-4" key={role}>
            <div className="pt-card h-100">
              <div className="d-flex align-items-center justify-content-between mb-2">
                <span className="pt-pill" style={{ background: roleBadgeStyle[role].bg, color: roleBadgeStyle[role].color, fontSize: '11.5px' }}>
                  {roleLabel[role]}
                </span>
                {isSuperAdmin && users && (
                  <span style={{ fontSize: '12px', color: 'var(--pt-text-muted)' }}>
                    {countByRole[role]} utilisateur{countByRole[role] > 1 ? 's' : ''}
                  </span>
                )}
              </div>
              <p style={{ fontSize: '12.5px', color: 'var(--pt-text-muted)', margin: 0 }}>{roleDescription[role]}</p>
            </div>
          </div>
        ))}
      </div>

      <div className="pt-card" style={{ padding: 0 }}>
        <div className="p-3" style={{ borderBottom: '1px solid var(--pt-border)' }}>
          <h6 style={{ fontSize: '14px', fontWeight: 600, margin: 0 }}>Matrice des permissions</h6>
        </div>
        <div className="pt-table-wrapper">
          <table className="pt-table">
            <thead>
              <tr>
                <th>Fonctionnalité</th>
                <th style={{ textAlign: 'center' }}>Observateur</th>
                <th style={{ textAlign: 'center' }}>Ingénieur Performance</th>
                <th style={{ textAlign: 'center' }}>Super Admin</th>
              </tr>
            </thead>
            <tbody>
              {capabilities.map((cap) => (
                <tr key={cap.label}>
                  <td style={{ fontSize: '12.5px' }}>{cap.label}</td>
                  {ROLES.map((role) => (
                    <td key={role} style={{ textAlign: 'center' }}>
                      {cap.roles.includes(role)
                        ? <i className="bi bi-check-circle-fill" style={{ color: 'var(--pt-success)' }}></i>
                        : <i className="bi bi-dash" style={{ color: 'var(--pt-text-light)' }}></i>}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}

export default RolesCatalog
