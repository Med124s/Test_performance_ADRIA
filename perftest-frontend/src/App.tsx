import { BrowserRouter, Routes, Route } from 'react-router-dom'
import MainLayout from './layouts/MainLayout'
import { AuthProvider } from './context/AuthContext'
import { ToastProvider } from './context/ToastContext'
import ProtectedRoute from './components/ProtectedRoute'
import RequireEditAccess from './components/RequireEditAccess'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import Applications from './pages/Applications'
import Scenarios from './pages/Scenarios'
import CreateScenario from './pages/CreateScenario'
import CreateScenarioLanding from './pages/CreateScenarioLanding'
import CreateStep from './pages/CreateStep'
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

function App() {
  return (
    <AuthProvider>
      <ToastProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/login" element={<Login />} />
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
              <Route path="scenarios/new" element={<RequireEditAccess><CreateScenarioLanding /></RequireEditAccess>} />
              <Route path="scenarios/create" element={<RequireEditAccess><CreateScenario /></RequireEditAccess>} />
              <Route path="scenarios/create-step" element={<RequireEditAccess><CreateStep /></RequireEditAccess>} />
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
            </Route>
          </Routes>
        </BrowserRouter>
      </ToastProvider>
    </AuthProvider>
  )
}

export default App
