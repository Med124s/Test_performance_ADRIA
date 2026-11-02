import { setUserTimezone } from '../utils/userTimezone'
import { useEffect, useState } from 'react'
import { Outlet } from 'react-router-dom'
import Sidebar from '../components/Sidebar'
import TopBarIcons from '../components/TopBarIcons'
import { useDarkMode } from '../utils/useDarkMode'
import { applyProfileTheme, setThemeSaver } from '../utils/theme'
import { profileBackendApi } from '../services/api/profileBackend'
import { ExecutionMonitorProvider } from '../context/ExecutionMonitorContext'

function MainLayout() {
  const { darkMode, toggleDarkMode } = useDarkMode()
  // Tiroir de navigation mobile / tablette.
  const [isMobileMenuOpen, setIsMobileMenuOpen] = useState(false)

  // Apparence et fuseau horaire du profil, chargés à la connexion.
  useEffect(() => {
    setThemeSaver((preference) => {
      profileBackendApi.updateTheme(preference.toUpperCase() as 'LIGHT' | 'DARK' | 'SYSTEM').catch(() => {})
    })
    profileBackendApi.get().then((p) => { applyProfileTheme(p.theme); setUserTimezone(p.timezone) }).catch(() => {})
    return () => setThemeSaver(null)
  }, [])

  // Suivi en direct des exécutions, conservé d'une page à l'autre.
  return (
    <ExecutionMonitorProvider>
      <Sidebar
        darkMode={darkMode}
        toggleDarkMode={toggleDarkMode}
        mobileOpen={isMobileMenuOpen}
        onCloseMobile={() => setIsMobileMenuOpen(false)}
      />
      <div className="pt-main">
        <TopBarIcons onOpenMobileMenu={() => setIsMobileMenuOpen(true)} />
        <Outlet />
      </div>
    </ExecutionMonitorProvider>
  )
}

export default MainLayout
