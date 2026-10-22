import { BackendNetworkResponse, BackendProxyMode } from '../../types/backendContracts'
import InfoTip from '../InfoTip'
import { formatDate } from '../../utils/dateFormat'
import { NetworkErrors, NetworkForm, PROXY_MODE_LABELS } from './networkModel'
import '../application-auth/applicationAuth.css'

// ============================================================
// Section « Réseau » du formulaire d'application : proxy du serveur
// (défaut, comme avant), aucun proxy, ou proxy propre à l'application ;
// autorité de certification TLS optionnelle (PEM).
// Même présentation que ApplicationAuthFields (mot de passe jamais
// réaffiché : vide = conservé).
// ============================================================

const MODES: { mode: BackendProxyMode; hint: string }[] = [
  { mode: 'INHERIT', hint: 'Proxy défini globalement sur le serveur LoadPilot, commun à toutes les applications qui choisissent cette option. Sans proxy configuré sur le serveur, la connexion est directe.' },
  { mode: 'NONE', hint: 'Connexion directe, même si le serveur a un proxy.' },
  { mode: 'CUSTOM', hint: 'Proxy propre à cette application (identification Basic).' },
]

interface Props {
  value: NetworkForm
  onChange: (value: NetworkForm) => void
  errors: NetworkErrors
  showErrors: boolean
  existing?: BackendNetworkResponse | null
}

export default function NetworkFields({ value, onChange, errors, showErrors, existing }: Props) {
  const set = (patch: Partial<NetworkForm>) => onChange({ ...value, ...patch })
  const err = (k: keyof NetworkErrors) => (showErrors || value.proxyHost || value.proxyPort ? errors[k] : undefined)
  const current = MODES.find((m) => m.mode === value.proxyMode) ?? MODES[0]
  const keptPassword = existing?.proxyMode === 'CUSTOM' && !!existing.proxyPasswordConfigured
  const savedCas = existing?.tlsCaCertificates ?? []

  return (
    <div className="aa-root">
      <div className="aa-segmented" role="radiogroup" aria-label="Proxy">
        {MODES.map((m) => (
          <button key={m.mode} type="button" role="radio" aria-checked={value.proxyMode === m.mode}
            className={value.proxyMode === m.mode ? 'active' : ''} onClick={() => set({ proxyMode: m.mode })}>
            {PROXY_MODE_LABELS[m.mode]}
          </button>
        ))}
      </div>
      <div className="aa-hint">{current.hint}</div>

      {value.proxyMode === 'CUSTOM' && (
        <div className="aa-fields">
          <div className="aa-row">
            <div className="aa-grow">
              <label className="aa-label" htmlFor="net-host">Hôte du proxy *</label>
              <input id="net-host" className={`pt-form-control aa-mono ${err('proxyHost') ? 'is-invalid' : ''}`} autoComplete="off"
                placeholder="ex : proxy.banque.local" value={value.proxyHost} onChange={(e) => set({ proxyHost: e.target.value })} />
              {err('proxyHost') && <div className="aa-error">{err('proxyHost')}</div>}
            </div>
            <div>
              <label className="aa-label" htmlFor="net-port">Port *</label>
              <input id="net-port" className={`pt-form-control ${err('proxyPort') ? 'is-invalid' : ''}`} inputMode="numeric" style={{ width: '100px' }}
                placeholder="8080" value={value.proxyPort} onChange={(e) => set({ proxyPort: e.target.value })} />
              {err('proxyPort') && <div className="aa-error">{err('proxyPort')}</div>}
            </div>
          </div>
          <div className="aa-row">
            <div className="aa-grow">
              <label className="aa-label" htmlFor="net-user">Identifiant <span style={{ fontWeight: 400 }}>(optionnel)</span></label>
              <input id="net-user" className={`pt-form-control ${errors.proxyUsername ? 'is-invalid' : ''}`} autoComplete="off"
                value={value.proxyUsername} onChange={(e) => set({ proxyUsername: e.target.value })} />
              {errors.proxyUsername && <div className="aa-error">{errors.proxyUsername}</div>}
            </div>
            <div className="aa-grow">
              <label className="aa-label" htmlFor="net-password">
                Mot de passe
                <InfoTip text="Chiffré en base, jamais réaffiché. Envoyé uniquement au proxy, jamais à l’application." />
              </label>
              <input id="net-password" className="pt-form-control" type="password" autoComplete="new-password"
                disabled={!value.proxyUsername.trim()}
                placeholder={keptPassword ? '•••••• enregistré — laisser vide pour conserver' : '••••••'}
                value={value.proxyPassword} onChange={(e) => set({ proxyPassword: e.target.value })} />
            </div>
          </div>
        </div>
      )}

      <div className="aa-fields">
        <label className="aa-label" htmlFor="net-ca">
          Autorité de certification TLS <span style={{ fontWeight: 400 }}>(optionnel)</span>
          <InfoTip text="Pour une application en HTTPS dont le certificat est émis par une autorité interne (inconnue de Java). Ajoutée à la confiance habituelle, pour cette application seulement. Certificat public uniquement, jamais de clé privée." />
        </label>
        <textarea id="net-ca" className={`pt-form-control aa-mono ${errors.tlsCaCertificate ? 'is-invalid' : ''}`} rows={4}
          spellCheck={false} placeholder={'-----BEGIN CERTIFICATE-----\n…\n-----END CERTIFICATE-----'}
          value={value.tlsCaCertificate ?? ''} onChange={(e) => set({ tlsCaCertificate: e.target.value })} />
        {errors.tlsCaCertificate && <div className="aa-error">{errors.tlsCaCertificate}</div>}
        <div className="aa-row" style={{ alignItems: 'center' }}>
          <label className="sw-chip" style={{ cursor: 'pointer' }}>
            <i className="bi bi-upload"></i> Charger un fichier (.pem, .crt)
            <input type="file" accept=".pem,.crt,.cer,text/plain" hidden aria-label="Charger le certificat de l’autorité"
              onChange={(e) => {
                const file = e.target.files?.[0]
                if (file) file.text().then((text) => set({ tlsCaCertificate: text })).catch(() => {})
                e.target.value = ''
              }} />
          </label>
          {value.tlsCaCertificate?.trim() && (
            <button type="button" className="sw-chip" onClick={() => set({ tlsCaCertificate: '' })}>Retirer</button>
          )}
        </div>
        {savedCas.length > 0 && value.tlsCaCertificate?.trim() === existing?.tlsCaCertificate?.trim() && (
          <ul className="aa-hint" data-testid="saved-cas" style={{ margin: 0, paddingLeft: '1.1rem' }}>
            {savedCas.map((c) => (
              <li key={c.subject + c.notAfter}>
                {c.subject} — {c.expired ? <strong className="text-danger">expiré le {formatDate(c.notAfter)}</strong> : `valable jusqu’au ${formatDate(c.notAfter)}`}
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}
