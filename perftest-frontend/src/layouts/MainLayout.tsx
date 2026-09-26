import { useState } from 'react'
import { Outlet } from 'react-router-dom'
import Sidebar from '../components/Sidebar'
import TopBarIcons from '../components/TopBarIcons'
import { useDarkMode } from '../utils/useDarkMode'

// P1-B — useScheduledExecutions() (100% frontend : setInterval + JSON
// Server, ne survit pas à la fermeture de l'onglet, voir son ancienne
// Javadoc) est DÉCOMMISSIONNÉ ici : la planification réelle vit désormais
// côté backend (voir ScheduledExecutionPoller, persistée, survit à un
// redémarrage). Les deux ne doivent JAMAIS tourner en même temps (double
// déclenchement d'un même Scenario planifié) — voir le rapport P1-B,
// section "Décommissionnement de l'ancien scheduler frontend".

function MainLayout() {
  const { darkMode, toggleDarkMode } = useDarkMode()
  // Tiroir mobile/tablette : fermé par défaut, ouvert via le bouton menu
  // de la TopBar, fermé au clic sur le fond ou après une navigation.
  const [isMobileMenuOpen, setIsMobileMenuOpen] = useState(false)

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
