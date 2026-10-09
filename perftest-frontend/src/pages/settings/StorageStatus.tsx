import type { BackendStoragePurge } from '../../types/backendContracts'
import { fullDate } from '../../utils/userDisplay'

// État d'une purge automatique (conservation des résultats, journal d'audit) : taille de la
// base, date et résultat de la dernière purge, heure de la prochaine.

/** « 87 Mo », « 1,2 Go », « 512 Ko ». */
export function formatBytes(bytes: number | null | undefined): string | null {
  if (bytes == null || !Number.isFinite(bytes)) return null
  const units = ['octets', 'Ko', 'Mo', 'Go', 'To']
  let value = bytes
  let i = 0
  while (value >= 1024 && i < units.length - 1) { value /= 1024; i++ }
  const digits = value >= 100 || i === 0 ? 0 : 1
  return `${value.toLocaleString('fr-FR', { maximumFractionDigits: digits })} ${units[i]}`
}

/** « aujourd’hui à 03:45 », « demain à 03:45 », « hier à 03:30 », sinon « le 12/10 à 03:45 ». */
export function formatNextRun(iso: string, now = new Date()): string {
  const d = new Date(iso)
  const time = d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })
  const day = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime()
  const diff = Math.round((day(d) - day(now)) / 86_400_000)
  if (diff === 0) return `aujourd’hui à ${time}`
  if (diff === 1) return `demain à ${time}`
  if (diff === -1) return `hier à ${time}`
  return `le ${d.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit' })} à ${time}`
}

/** Même format, avec une majuscule : « Aujourd’hui à 03:30 ». */
export function formatRunDate(iso: string, now = new Date()): string {
  const text = formatNextRun(iso, now)
  return text.charAt(0).toUpperCase() + text.slice(1)
}

interface Props {
  purge: BackendStoragePurge | null | undefined
  databaseSizeBytes?: number | null
}

export default function StorageStatus({ purge, databaseSizeBytes }: Props) {
  const size = formatBytes(databaseSizeBytes)
  return (
    <div className="st-storage" aria-label="État du stockage">
      {databaseSizeBytes !== undefined && (
        <div className="st-storage-row"><i className="bi bi-database" aria-hidden="true"></i>
          Taille actuelle de la base : <strong>{size ?? 'inconnue'}</strong></div>
      )}
      <div className="st-storage-row"><i className="bi bi-clock-history" aria-hidden="true"></i>
        Dernière purge : {purge?.lastRunAt
          ? <><strong><time dateTime={purge.lastRunAt} title={fullDate(purge.lastRunAt) ?? undefined}>{formatRunDate(purge.lastRunAt)}</time></strong>
            {purge.lastSummary && <> — {purge.lastSummary}</>}</>
          : <strong>pas encore de passage enregistré</strong>}
      </div>
      <div className="st-storage-row"><i className="bi bi-calendar-event" aria-hidden="true"></i>
        Prochaine purge : <strong>{purge?.nextRunAt ? formatNextRun(purge.nextRunAt) : 'désactivée'}</strong></div>
    </div>
  )
}
