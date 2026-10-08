import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import TopBar from '../components/TopBar'
import Pagination from '../components/Pagination'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import { auditBackendApi, AuditLogFilters, SYSTEM_ACTOR } from '../services/api/auditBackend'
import { ApiError } from '../services/api/httpClient'
import type { BackendAuditCategory, BackendAuditLogResponse, BackendAuditResult, BackendPagedResponse } from '../types/backendContracts'
import { avatarTone, fullDate, initialsOf, relativeTime } from '../utils/userDisplay'
import AuditDetailPanel, { targetLink } from './audit/AuditDetailPanel'
import { parseFrenchDate } from './audit/auditDates'
import './users/users.css'
import './audit/audit.css'

// ============================================================
// « Journal d'audit » : qui a fait quoi, et quand. Réservé au Super
// administrateur (vérifié par le serveur, voir AuditLogController).
// Tous les filtres sont appliqués côté serveur et gardés dans l'URL
// (lien partageable, retour arrière). Lecture seule : aucune entrée ne
// peut être modifiée ni supprimée.
// ============================================================

const PAGE_SIZE = 20

export const AUDIT_CATEGORIES: { value: BackendAuditCategory; label: string }[] = [
  { value: 'APPLICATIONS', label: 'Applications' },
  { value: 'SCENARIOS', label: 'Scénarios' },
  { value: 'EXECUTIONS', label: 'Exécutions' },
  { value: 'SCHEDULING', label: 'Planification' },
  { value: 'USERS', label: 'Utilisateurs' },
  { value: 'SECURITY', label: 'Sécurité' },
  { value: 'SETTINGS', label: 'Paramètres' },
]

type Period = '' | '24h' | '7j' | '30j' | 'perso'
const PERIODS: { value: Period; label: string }[] = [
  { value: '', label: 'Toute la période' },
  { value: '24h', label: 'Dernières 24 h' },
  { value: '7j', label: '7 derniers jours' },
  { value: '30j', label: '30 derniers jours' },
  { value: 'perso', label: 'Personnalisée…' },
]
const PERIOD_HOURS: Record<string, number> = { '24h': 24, '7j': 24 * 7, '30j': 24 * 30 }

function describeError(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 401) return 'Vous devez être connecté pour consulter le journal.'
    if (err.status === 403) return 'Le journal d’audit est réservé au Super administrateur.'
    if (err.status === 0 || err.status >= 500) return 'Le serveur LoadPilot ne répond pas. Réessayez dans un instant.'
    return err.message
  }
  return 'Impossible de charger le journal.'
}

export default function AuditLogs() {
  const { authProvider, rawRoles } = useAuth()
  const { showToast } = useToast()
  const canView = authProvider === 'keycloak' && rawRoles.includes('ROLE_SUPER_ADMIN')
  const [params, setParams] = useSearchParams()

  // Les filtres vivent dans l'URL (source de vérité).
  const q = params.get('q') ?? ''
  const actor = params.get('utilisateur') ?? ''
  const category = (params.get('categorie') ?? '') as BackendAuditCategory | ''
  const result = (params.get('resultat') ?? '') as BackendAuditResult | ''
  const period = (params.get('periode') ?? '') as Period
  const customFrom = params.get('du') ?? ''
  const customTo = params.get('au') ?? ''
  const page = Math.max(1, Number(params.get('page') ?? '1') || 1)
  const detailId = params.get('entree')

  const [searchDraft, setSearchDraft] = useState(q)
  const [fromDraft, setFromDraft] = useState(customFrom)
  const [toDraft, setToDraft] = useState(customTo)
  const [data, setData] = useState<BackendPagedResponse<BackendAuditLogResponse> | null>(null)
  const [actors, setActors] = useState<string[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [exporting, setExporting] = useState(false)
  const requestId = useRef(0)

  const update = useCallback((changes: Record<string, string | null>, keepPage = false) => {
    setParams((prev) => {
      const next = new URLSearchParams(prev)
      for (const [key, value] of Object.entries(changes)) {
        if (value) next.set(key, value)
        else next.delete(key)
      }
      if (!keepPage) next.delete('page')
      return next
    }, { replace: true })
  }, [setParams])

  // Recherche libre : appliquée 300 ms après la dernière frappe.
  useEffect(() => { setSearchDraft(q) }, [q])
  useEffect(() => { setFromDraft(customFrom) }, [customFrom])
  useEffect(() => { setToDraft(customTo) }, [customTo])
  useEffect(() => {
    if (searchDraft.trim() === q) return
    const timer = window.setTimeout(() => update({ q: searchDraft.trim() || null }), 300)
    return () => window.clearTimeout(timer)
  }, [searchDraft, q, update])

  // Dates personnalisées : appliquées dès qu'elles sont complètes et valides.
  useEffect(() => {
    if (period !== 'perso') return
    const from = fromDraft.trim()
    const to = toDraft.trim()
    const fromOk = !from || parseFrenchDate(from) !== null
    const toOk = !to || parseFrenchDate(to, true) !== null
    if (fromOk && toOk && (from !== customFrom || to !== customTo)) update({ du: from || null, au: to || null })
  }, [period, fromDraft, toDraft, customFrom, customTo, update])

  const fromInvalid = period === 'perso' && fromDraft.trim() !== '' && parseFrenchDate(fromDraft) === null
  const toInvalid = period === 'perso' && toDraft.trim() !== '' && parseFrenchDate(toDraft, true) === null
  const fromDate = customFrom ? parseFrenchDate(customFrom) : null
  const toDate = customTo ? parseFrenchDate(customTo, true) : null
  const rangeInvalid = period === 'perso' && fromDate !== null && toDate !== null && fromDate > toDate

  const filters: AuditLogFilters = useMemo(() => {
    const f: AuditLogFilters = { q: q || undefined, actor: actor || undefined, category: category || undefined, result: result || undefined }
    if (PERIOD_HOURS[period]) {
      f.from = new Date(Date.now() - PERIOD_HOURS[period] * 3_600_000).toISOString()
    } else if (period === 'perso') {
      if (fromDate) f.from = fromDate.toISOString()
      if (toDate) f.to = toDate.toISOString()
    }
    return f
    // La fenêtre glissante (24 h…) est recalculée à chaque changement de filtre ou de page.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q, actor, category, result, period, customFrom, customTo, page])

  useEffect(() => {
    if (!canView || rangeInvalid) return
    const id = ++requestId.current
    setLoading(true)
    setError(null)
    auditBackendApi.list(filters, page - 1, PAGE_SIZE)
      .then((res) => { if (id === requestId.current) setData(res) })
      .catch((err) => { if (id === requestId.current) setError(describeError(err)) })
      .finally(() => { if (id === requestId.current) setLoading(false) })
  }, [canView, filters, page, rangeInvalid])

  useEffect(() => {
    if (!canView) return
    auditBackendApi.actors().then(setActors).catch(() => setActors([]))
  }, [canView])

  const filtersActive = !!(q || actor || category || result || period)
  const reset = () => {
    setSearchDraft('')
    setFromDraft('')
    setToDraft('')
    setParams(new URLSearchParams(), { replace: true })
  }

  const exportCsv = async () => {
    setExporting(true)
    try {
      const blob = await auditBackendApi.exportCsv(filters)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `journal-audit-${new Date().toISOString().slice(0, 10)}.csv`
      document.body.appendChild(a)
      a.click()
      a.remove()
      URL.revokeObjectURL(url)
      showToast(filtersActive ? 'Export CSV téléchargé (avec les filtres en cours).' : 'Export CSV téléchargé.', 'success')
    } catch (err) {
      showToast(describeError(err), 'danger')
    } finally {
      setExporting(false)
    }
  }

  const rows = data?.content ?? []
  const total = data?.totalElements ?? 0
  const totalPages = Math.max(1, data?.totalPages ?? 1)
  const selected = detailId ? rows.find((r) => r.id === detailId) ?? null : null

  const header = (withExport: boolean) => (
    <div className="pt-page-header au-header">
      <div className="page-title">
        <h1>Journal d’audit</h1>
        <p>Qui a fait quoi, et quand</p>
      </div>
      <div className="d-flex align-items-center gap-2">
        {withExport && (
          <button type="button" className="pt-btn-outline" onClick={exportCsv} disabled={exporting || total === 0}>
            <i className={`bi ${exporting ? 'bi-arrow-repeat pt-spin' : 'bi-download'}`} aria-hidden="true"></i>
            {exporting ? 'Export…' : 'Exporter en CSV'}
          </button>
        )}
        <TopBar searchPlaceholder="" />
      </div>
    </div>
  )

  if (!canView) {
    return (
      <div className="pt-content">
        {header(false)}
        <div className="pt-card">
          <div className="pt-empty-state" style={{ padding: '4rem 1.5rem' }}>
            <i className="bi bi-shield-lock" style={{ fontSize: '40px', color: 'var(--pt-text-light)' }} aria-hidden="true"></i>
            <p style={{ fontSize: '15px', fontWeight: 600, color: 'var(--pt-text)' }}>Accès restreint</p>
            <p>Le journal d’audit est réservé au Super administrateur.</p>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="pt-content">
      {header(true)}

      <div className="au-filters" role="search" aria-label="Filtrer le journal">
        <div className="au-search">
          <i className="bi bi-search" aria-hidden="true"></i>
          <input type="search" className="pt-form-control" placeholder="Rechercher (utilisateur, objet, description)…"
            aria-label="Rechercher dans le journal" value={searchDraft} onChange={(e) => setSearchDraft(e.target.value)} />
        </div>
        <select className="pt-form-control" aria-label="Utilisateur" value={actor} onChange={(e) => update({ utilisateur: e.target.value || null })}>
          <option value="">Tous les utilisateurs</option>
          <option value={SYSTEM_ACTOR}>Système (actions automatiques)</option>
          {actors.map((a) => <option key={a} value={a}>{a}</option>)}
        </select>
        <select className="pt-form-control" aria-label="Catégorie" value={category} onChange={(e) => update({ categorie: e.target.value || null })}>
          <option value="">Toutes les catégories</option>
          {AUDIT_CATEGORIES.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
        </select>
        <select className="pt-form-control" aria-label="Résultat" value={result} onChange={(e) => update({ resultat: e.target.value || null })}>
          <option value="">Tous les résultats</option>
          <option value="SUCCESS">Réussi</option>
          <option value="FAILURE">Échec</option>
        </select>
        <select className="pt-form-control" aria-label="Période" value={period}
          onChange={(e) => update({ periode: e.target.value || null, du: null, au: null })}>
          {PERIODS.map((p) => <option key={p.value} value={p.value}>{p.label}</option>)}
        </select>
        {period === 'perso' && (
          <div className="au-dates">
            <input className={`pt-form-control${fromInvalid ? ' is-invalid' : ''}`} placeholder="jj/mm/aaaa" aria-label="Du (jj/mm/aaaa)"
              inputMode="numeric" maxLength={10} value={fromDraft} onChange={(e) => setFromDraft(e.target.value)} />
            <span aria-hidden="true">→</span>
            <input className={`pt-form-control${toInvalid ? ' is-invalid' : ''}`} placeholder="jj/mm/aaaa" aria-label="Au (jj/mm/aaaa)"
              inputMode="numeric" maxLength={10} value={toDraft} onChange={(e) => setToDraft(e.target.value)} />
          </div>
        )}
        {filtersActive && (
          <button type="button" className="pt-btn-ghost au-reset" onClick={reset}>
            <i className="bi bi-x-lg" aria-hidden="true"></i> Réinitialiser
          </button>
        )}
      </div>
      {(fromInvalid || toInvalid) && <p className="au-filter-error" role="alert">Date attendue au format jj/mm/aaaa.</p>}
      {rangeInvalid && <p className="au-filter-error" role="alert">La date de début doit précéder la date de fin.</p>}

      {error && (
        <div className="pt-alert-banner danger mb-3" role="alert">
          <i className="bi bi-exclamation-triangle-fill" aria-hidden="true"></i>{error}
        </div>
      )}

      <div className="pt-card au-card">
        {loading && !data ? (
          <div className="au-empty"><i className="bi bi-arrow-repeat pt-spin" aria-hidden="true"></i><p>Chargement du journal…</p></div>
        ) : rows.length === 0 ? (
          <div className="au-empty">
            <i className="bi bi-journal-text" aria-hidden="true"></i>
            <p>{filtersActive ? 'Aucune entrée ne correspond à ces filtres.' : 'Le journal est vide pour le moment.'}</p>
            {filtersActive && <button type="button" className="pt-btn-outline" onClick={reset}>Réinitialiser les filtres</button>}
          </div>
        ) : (
          <table className="au-table" aria-busy={loading}>
            <thead>
              <tr>
                <th scope="col">Date</th>
                <th scope="col">Utilisateur</th>
                <th scope="col">Action</th>
                <th scope="col" className="au-th-center">Résultat</th>
                <th scope="col"><span className="visually-hidden">Détail</span></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => <AuditRow key={row.id} row={row} onOpen={() => update({ entree: row.id }, true)} />)}
            </tbody>
          </table>
        )}
        {rows.length > 0 && (
          <Pagination page={page} totalPages={totalPages} onPageChange={(p) => update({ page: p > 1 ? String(p) : null }, true)}
            startIndex={(page - 1) * PAGE_SIZE + 1} endIndex={(page - 1) * PAGE_SIZE + rows.length} totalItems={total} itemLabel="entrées" />
        )}
      </div>

      {selected && <AuditDetailPanel entry={selected} onClose={() => update({ entree: null }, true)} />}
    </div>
  )
}

export function AuditActor({ row }: { row: BackendAuditLogResponse }) {
  if (row.actorType === 'SYSTEM') {
    return (
      <div className="au-actor">
        <span className="au-avatar-system" aria-hidden="true"><i className="bi bi-gear-fill"></i></span>
        <span className="au-actor-text"><strong>Système</strong>{row.actorSource && <small>{row.actorSource}</small>}</span>
      </div>
    )
  }
  const name = row.username ?? 'Utilisateur inconnu'
  return (
    <div className="au-actor">
      <span className={`ur-avatar au-avatar ur-avatar--${avatarTone(row.userId ?? name)}`} aria-hidden="true">{initialsOf(name)}</span>
      <span className="au-actor-text"><strong>{name}</strong>{row.actorSource && <small>{row.actorSource}</small>}</span>
    </div>
  )
}

/** Ligne de détail sous l'action : premier « avant → après », sinon le détail en français.
 * Une ancienne entrée (texte d'origine non traduit) n'a pas de ligne : son texte reste dans le panneau. */
export function auditDetailLine(row: BackendAuditLogResponse): string | null {
  if (row.legacy) return null
  const first = row.changes[0]
  if (first) {
    const others = row.changes.length - 1
    const more = others > 0 ? ` (+${others} autre${others > 1 ? 's' : ''})` : ''
    return `${first.label} : ${first.before ?? '—'} → ${first.after ?? '—'}${more}`
  }
  return row.detail
}

function AuditRow({ row, onOpen }: { row: BackendAuditLogResponse; onOpen: () => void }) {
  const link = row.targetExists ? targetLink(row) : null
  const detail = auditDetailLine(row)
  return (
    <tr className="au-row">
      <td className="au-cell-date">
        <time dateTime={row.date} title={fullDate(row.date) ?? undefined}>{relativeTime(row.date)}</time>
      </td>
      <td className="au-cell-actor"><AuditActor row={row} /></td>
      <td className="au-cell-action">
        <span className="au-summary">
          {link ? <Link to={link} title="Ouvrir l’élément concerné">{row.summary}</Link> : row.summary}
        </span>
        {detail && <span className="au-detail">{detail}</span>}
      </td>
      <td className="au-cell-result">
        {row.result === 'SUCCESS'
          ? <span className="au-result ok" title="Réussi"><i className="bi bi-check-lg" aria-hidden="true"></i><span className="visually-hidden">Réussi</span></span>
          : <span className="au-result ko" title="Échec"><i className="bi bi-x-lg" aria-hidden="true"></i><span className="visually-hidden">Échec</span></span>}
      </td>
      <td className="au-cell-open">
        <button type="button" className="au-icon-btn" onClick={onOpen} aria-label={`Voir le détail : ${row.summary}`} title="Voir le détail">
          <i className="bi bi-eye" aria-hidden="true"></i>
        </button>
      </td>
    </tr>
  )
}
