import { Navigate, Route, RouterProvider, createBrowserRouter, createRoutesFromElements } from 'react-router-dom'
import MainLayout from './layouts/MainLayout'
import { AuthProvider } from './context/AuthContext'
import { ToastProvider } from './context/ToastContext'
import ProtectedRoute from './components/ProtectedRoute'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import Applications from './pages/Applications'
import Scenarios from './pages/Scenarios'
import ScenarioWizard from './pages/scenario-wizard/ScenarioWizard'
import Executions from './pages/Executions'
import ExecutionReport from './pages/ExecutionReport'
import ExecutionDetail from './pages/ExecutionDetail'
import ExecutionLive from './pages/ExecutionLive'
import ExecutionCompare from './pages/ExecutionCompare'
import Reports from './pages/Reports'
import NotificationsPage from './pages/NotificationsPage'
import NotificationsPreferences from './pages/NotificationsPreferences'
import UtilisateursRoles from './pages/UtilisateursRoles'
import AuditLogs from './pages/AuditLogs'
import Parametres from './pages/Parametres'
import ScheduledExecutions from './pages/ScheduledExecutions'
import ScheduleHistory from './pages/scheduling/ScheduleHistory'
import RouteErrorPage, { NotFoundPage } from './pages/RouteErrorPage'

// Routeur « data » (createBrowserRouter) : nécessaire à useBlocker, qui permet à
// l'assistant « Nouveau scénario » d'intercepter toute navigation (liens du menu,
// bouton retour du navigateur) tant que du travail n'est pas enregistré.
const router = createBrowserRouter(
  createRoutesFromElements(
    <>
    <Route path="/login" element={<Login />} errorElement={<RouteErrorPage fullPage />} />
    <Route
      path="/"
      element={
        <ProtectedRoute>
          <MainLayout />
        </ProtectedRoute>
      }
      errorElement={<RouteErrorPage fullPage />}
    >
      {/* Erreur dans une page : message clair dans la mise en page (menu conservé), jamais l'écran technique. */}
      <Route errorElement={<RouteErrorPage />}>
      <Route index element={<Dashboard />} />
      <Route path="applications" element={<Applications />} />
      <Route path="scenarios" element={<Scenarios />} />
      {/* P1-G — l'assistant de création (CreateScenarioLanding/CreateScenario/
          CreateStep, JSON Server) est retiré : la création/édition de
          Scénarios et Steps réels se fait désormais uniquement depuis
          /scenarios (Spring Boot), qui couvre la même fonctionnalité
          (métadonnées, paramètres de charge, Steps, lancement). Redirection
          pour tout lien/marque-page existant vers les anciennes routes. */}
      <Route path="scenarios/new" element={<ScenarioWizard />} />
      <Route path="scenarios/:id/edit" element={<ScenarioWizard mode="edit" />} />
      <Route path="scenarios/create" element={<Navigate to="/scenarios" replace />} />
      <Route path="scenarios/create-step" element={<Navigate to="/scenarios" replace />} />
      <Route path="executions" element={<Executions />} />
      <Route path="executions/report/:id" element={<ExecutionReport />} />
      <Route path="executions/detail/:id" element={<ExecutionDetail />} />
      <Route path="executions/live/:id" element={<ExecutionLive />} />
      <Route path="executions/compare" element={<ExecutionCompare />} />
      {/* Page « Métriques » supprimée (doublon du Dashboard et du rapport) : anciennes adresses -> Dashboard. */}
      <Route path="metriques/*" element={<Navigate to="/" replace />} />
      {/* Page « Historique » supprimée (doublon de la page Exécutions) : anciennes adresses -> Exécutions. */}
      <Route path="historique/*" element={<Navigate to="/executions" replace />} />
      <Route path="notifications" element={<NotificationsPage />} />
      <Route path="notifications/preferences" element={<NotificationsPreferences />} />
      {/* « Utilisateurs et rôles » : Users & Roles + Catalogue des rôles (onglets) ; anciennes adresses redirigées. */}
      <Route path="utilisateurs" element={<UtilisateursRoles />} />
      <Route path="users-roles" element={<Navigate to="/utilisateurs?tab=utilisateurs" replace />} />
      <Route path="roles-catalog" element={<Navigate to="/utilisateurs?tab=roles" replace />} />
      <Route path="audit-logs" element={<AuditLogs />} />
      {/* « Paramètres » : Settings + Configurations + Intégrations (onglets) ; anciennes adresses redirigées. */}
      <Route path="parametres" element={<Parametres />} />
      <Route path="settings" element={<Navigate to="/parametres?tab=profil" replace />} />
      <Route path="settings/integrations" element={<Navigate to="/parametres?tab=integrations" replace />} />
      <Route path="configurations" element={<Navigate to="/parametres?tab=systeme" replace />} />
      {/* Rapports : résultats des tests terminés (consulter, exporter en PDF, comparer). Ancienne adresse anglaise redirigée. */}
      <Route path="rapports" element={<Reports />} />
      <Route path="reports" element={<Navigate to="/rapports" replace />} />
      <Route path="planification" element={<ScheduledExecutions />} />
      <Route path="planification/:id" element={<ScheduleHistory />} />
      {/* Adresse inconnue : page « introuvable » (même page d'erreur). */}
      <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Route>
    </>
  )
)

function App() {
  return (
    <AuthProvider>
      <ToastProvider>
        <RouterProvider router={router} />
      </ToastProvider>
    </AuthProvider>
  )
}

export default App