import { useState } from 'react'
import { VariableRow, validateScenarioVariables } from './variablesModel'

interface ScenarioVariablesPanelProps {
  rows: VariableRow[]
  csvColumns: string[]
  onChange: (rows: VariableRow[]) => void
  /** Ouvert d'office (page de modification) ; replié par défaut dans l'assistant. */
  defaultOpen?: boolean
}

/** Variables du scénario (comme un environnement Postman) : constantes clé/valeur, 3e source après capture et CSV. */
function ScenarioVariablesPanel({ rows, csvColumns, onChange, defaultOpen = false }: ScenarioVariablesPanelProps) {
  const [open, setOpen] = useState(defaultOpen || rows.length > 0)
  const { errors, warnings } = validateScenarioVariables(rows, csvColumns)
  const count = rows.filter((r) => r.key.trim()).length
  const update = (i: number, patch: Partial<VariableRow>) => onChange(rows.map((r, idx) => (idx === i ? { ...r, ...patch } : r)))

  return (
    <div className="sw-csv">
      <button type="button" className="sw-csv-toggle" onClick={() => setOpen((v) => !v)} aria-expanded={open}>
        <i className="bi bi-sliders"></i>
        <span>Variables du scénario <span className="sw-optional">optionnel</span></span>
        {errors.length > 0 ? <span className="sw-tab-dot" aria-label="Erreur"></span> : count > 0 && <span className="sw-tab-badge">{count}</span>}
        <i className={`bi bi-chevron-${open ? 'up' : 'down'} ms-auto`}></i>
      </button>
      {open && (
        <div className="sw-csv-body">
          <div className="sw-field-hint mb-2">
            Constantes communes à toutes les étapes (ex. <code>currency</code> = <code>MAD</code>), utilisables comme
            <code> ${'{'}nom{'}'}</code>. Une capture ou une colonne CSV de même nom est prioritaire.
          </div>
          <div className="d-flex flex-column gap-2">
            {rows.map((row, i) => (
              <div key={i} className="sw-kv-row">
                <input className="pt-form-control" placeholder="Nom (ex : currency)" value={row.key} spellCheck={false}
                  onChange={(e) => update(i, { key: e.target.value })} aria-label={`Nom de la variable ${i + 1}`} />
                <input className="pt-form-control" placeholder="Valeur (ex : MAD)" value={row.value} spellCheck={false}
                  onChange={(e) => update(i, { value: e.target.value })} aria-label={`Valeur de la variable ${row.key || i + 1}`} />
                <button type="button" className="sw-icon-btn danger" onClick={() => onChange(rows.filter((_, idx) => idx !== i))} aria-label="Supprimer la variable">
                  <i className="bi bi-x-lg"></i>
                </button>
              </div>
            ))}
          </div>
          {errors.map((e) => <div key={e} className="sw-field-error">{e}</div>)}
          {warnings.length > 0 && (
            <div className="sw-inline-alert warn mt-2">
              <i className="bi bi-exclamation-triangle"></i>
              <span>{warnings.map((w) => <span key={w} className="d-block">{w}</span>)}</span>
            </div>
          )}
          <button type="button" className="sw-chip mt-2" onClick={() => onChange([...rows, { key: '', value: '' }])}>
            <i className="bi bi-plus-lg"></i> Ajouter une variable
          </button>
        </div>
      )}
    </div>
  )
}

export default ScenarioVariablesPanel
