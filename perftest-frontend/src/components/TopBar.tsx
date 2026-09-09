interface TopBarProps {
  searchPlaceholder?: string
}

function TopBar({ searchPlaceholder = 'Rechercher...' }: TopBarProps) {
  if (!searchPlaceholder) return null

  return (
    <div className="pt-topbar">
      <div className="pt-search">
        <i className="bi bi-search"></i>
        <input type="text" placeholder={searchPlaceholder} />
      </div>
    </div>
  )
}

export default TopBar
