import { describe, expect, it } from 'vitest'
import messagesFr from '../../../keycloak/themes/loadpilot/login/messages/messages_fr.properties?raw'
import loginFtl from '../../../keycloak/themes/loadpilot/login/login.ftl?raw'
import realmExport from '../../../keycloak/realm-export.json?raw'
import compose from '../../../docker-compose.yml?raw'

const message = (key: string) => messagesFr.match(new RegExp(`^${key}=(.*)$`, 'm'))?.[1]

describe('Thème Keycloak LoadPilot et réalm', () => {
  it('compte verrouillé : même message générique que des identifiants incorrects, texte fixe sous le formulaire', () => {
    const generic = 'Nom d\'\'utilisateur ou mot de passe incorrect.'
    expect(message('invalidUserMessage')).toBe(generic)
    expect(message('accountTemporarilyDisabledMessage')).toBe(generic)
    expect(message('accountPermanentlyDisabledMessage')).toBe(generic)
    // Aucun texte affiché (hors commentaires) ne révèle qu'un compte est verrouillé.
    const shownTexts = messagesFr.split(/\r?\n/).filter((l) => !l.startsWith('#')).join('\n')
    expect(shownTexts).not.toMatch(/verrouill/i)
    expect(message('lpLockoutHint')).toBe('Après plusieurs tentatives échouées, l\'\'accès peut être temporairement bloqué. Réessayez dans quelques minutes ou contactez un administrateur.')
    expect(loginFtl).toContain('${msg("lpLockoutHint")}')
  })

  it('réalm : protection contre la force brute (10 essais, 1 min, 15 min au plus) et SMTP lu dans les variables KC_SMTP_*', () => {
    const realm = JSON.parse(realmExport)
    expect(realm).toMatchObject({ bruteForceProtected: true, permanentLockout: false, failureFactor: 10, waitIncrementSeconds: 60, maxFailureWaitSeconds: 900, resetPasswordAllowed: true })
    expect(realm.smtpServer).toMatchObject({ host: '${KC_SMTP_HOST:mailpit}', port: '${KC_SMTP_PORT:1025}', password: '${KC_SMTP_PASSWORD:}' })
  })

  it('docker compose : Mailpit (interface 8025) et variables SMTP remplaçables', () => {
    expect(compose).toMatch(/mailpit:\r?\n\s+image: axllent\/mailpit:/)
    expect(compose).toContain('"8025:8025"')
    expect(compose).toContain('KC_SMTP_HOST: ${KC_SMTP_HOST:-mailpit}')
    expect(compose).toContain('KC_SMTP_PASSWORD: ${KC_SMTP_PASSWORD:-}')
  })
})
