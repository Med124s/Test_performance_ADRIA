// Page de connexion ouverte par LoadPilot après une déconnexion : message « Vous avez été déconnecté. »
const loggedOut = document.getElementById('lp-logged-out')
if (loggedOut && new URLSearchParams(window.location.search).get('lp_reason') === 'logged_out') {
  loggedOut.hidden = false
}

// Bouton « Afficher / masquer le mot de passe » des champs LoadPilot (lp.ftl).
document.querySelectorAll('[data-lp-reveal]').forEach((button) => {
  const input = document.getElementById(button.getAttribute('aria-controls'))
  if (!input) return
  const show = button.querySelector('.lp-reveal-show')
  const hide = button.querySelector('.lp-reveal-hide')
  button.addEventListener('click', () => {
    const reveal = input.type === 'password'
    input.type = reveal ? 'text' : 'password'
    button.setAttribute('aria-pressed', String(reveal))
    button.setAttribute('aria-label', reveal ? button.dataset.labelHide : button.dataset.labelShow)
    show.hidden = reveal
    hide.hidden = !reveal
    input.focus()
  })
})
