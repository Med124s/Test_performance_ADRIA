import { useState } from 'react'
import { Outlet } from 'react-router-dom'
import Sidebar from '../components/Sidebar'
import TopBarIcons from '../components/TopBarIcons'
import { useScheduledExecutions } from '../hooks/useScheduledExecutions'

function MainLayout() {
  // Déclenche réellement les scénarios planifiés dès que leur date/heure
  // arrive — actif sur toutes les pages authentifiées (voir le hook).
  useScheduledExecutions()

  const [darkMode, setDarkMode] = useState(false)
  // Tiroir mobile/tablette : fermé par défaut, ouvert via le bouton menu
  // de la TopBar, fermé au clic sur le fond ou après une navigation.
  const [isMobileMenuOpen, setIsMobileMenuOpen] = useState(false)

  const toggleDarkMode = () => {
    setDarkMode(!darkMode)
    document.documentElement.setAttribute('data-theme', !darkMode ? 'dark' : 'light')
  }

  return (
    <>
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
    </>
  )
}

export default MainLayout
