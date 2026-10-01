import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import MainLayout from './layouts/MainLayout'
import { AuthProvider } from './context/AuthContext'
import { ToastProvider } from './context/ToastContext'
import ProtectedRoute from './components/ProtectedRoute'
import Dashboard from './pages/Dashboard'
import Applications from './pages/Applications'
import Scenarios from './pages/Scenarios'
import ScenarioLanding from './pages/ScenarioLanding'
import ScenarioWizard from './pages/ScenarioWizard'
import ScenarioStepEditor from './pages/ScenarioStepEditor'
import Configurations from './pages/Configurations'
import Executions from './pages/Executions'
import ExecutionReport from './pages/ExecutionReport'
import ExecutionDetail from './pages/ExecutionDetail'
import Metriques from './pages/Metriques'
import Historique from './pages/Historique'
import NotificationsPage from './pages/NotificationsPage'
import NotificationsPreferences from './pages/NotificationsPreferences'
import UsersRoles from './pages/UsersRoles'
import RolesCatalog from './pages/RolesCatalog'
import AuditLogs from './pages/AuditLogs'
import Settings from './pages/Settings'
import SettingsIntegrations from './pages/SettingsIntegrations'
import Rapports from './pages/Rapports'
import ScheduledExecutions from './pages/ScheduledExecutions'

function App() {
  return (
    <AuthProvider>
      <ToastProvider>
        <BrowserRouter>
          <Routes>
            <Route
              path="/"
              element={
                <ProtectedRoute>
                  <MainLayout />
                </ProtectedRoute>
              }
            >
              <Route index element={<Dashboard />} />
              <Route path="applications" element={<Applications />} />
              <Route path="scenarios" element={<Scenarios />} />
              {/* Restauration du design ancien (workflow en assistant/stepper) —
                  voir rapport d'analyse dédié : ces 3 routes reprennent les noms
                  et le rôle des anciennes pages CreateScenarioLanding/
                  CreateScenario/CreateStep, mais parlent exclusivement au
                  backend Spring Boot réel (scenariosBackendApi/stepsBackendApi),
                  jamais à JSON Server. Le contrôle d'accès en écriture (RBAC) se
                  fait comme partout ailleurs dans cette version DANS chaque page
                  (canWrite), pas via un composant wrapper de route dédié
                  (RequireEditAccess a été retiré de ce projet, voir P1-O). */}
              <Route path="scenarios/new" element={<ScenarioLanding />} />
              <Route path="scenarios/create" element={<ScenarioWizard />} />
              <Route path="scenarios/create-step" element={<ScenarioStepEditor />} />
              <Route path="configurations" element={<Configurations />} />
              <Route path="executions" element={<Executions />} />
              <Route path="executions/report/:id" element={<ExecutionReport />} />
              <Route path="executions/detail/:id" element={<ExecutionDetail />} />
              <Route path="metriques" element={<Metriques />} />
              <Route path="historique" element={<Historique />} />
              <Route path="notifications" element={<NotificationsPage />} />
              <Route path="notifications/preferences" element={<NotificationsPreferences />} />
              <Route path="users-roles" element={<UsersRoles />} />
              <Route path="roles-catalog" element={<RolesCatalog />} />
              <Route path="audit-logs" element={<AuditLogs />} />
              <Route path="settings" element={<Settings />} />
              <Route path="settings/integrations" element={<SettingsIntegrations />} />
              <Route path="rapports" element={<Rapports />} />
              <Route path="planification" element={<ScheduledExecutions />} />
            </Route>
          </Routes>
        </BrowserRouter>
      </ToastProvider>
    </AuthProvider>
  )
}

export default App
