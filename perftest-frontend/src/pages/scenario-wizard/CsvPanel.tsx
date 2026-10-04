import { useRef, useState } from 'react'
import { BackendCsvDistribution } from '../../types/backendContracts'
import { DraftStep } from './wizardModel'
import { analyzeCsv, csvTemplate, readCsvFile, templateColumns, undefinedVariables } from './csvModel'

interface CsvPanelProps {
  csvData: string
  fileName: string | null
  steps: DraftStep[]
  authTokenAvailable: boolean
  virtualUsers: number | null
  /** Noms des variables du scénario (comptent comme variables connues). */
  scenarioVariables: string[]
  distribution: BackendCsvDistribution
  onDistributionChange: (distribution: BackendCsvDistribution) => void
  onChange: (csvData: string, fileName: string | null) => void
}

const PREVIEW_ROWS = 5

/** Jeu de données CSV : saisie, import de fichier (bouton ou glisser-déposer), aperçu et cohérence avec les étapes. */
function CsvPanel({ csvData, fileName, steps, authTokenAvailable, virtualUsers, scenarioVariables, distribution, onDistributionChange, onChange }: CsvPanelProps) {
  const inputRef = useRef<HTMLInputElement>(null)
  const [open, setOpen] = useState(!!csvData.trim())
  const [dragOver, setDragOver] = useState(false)
  const [importError, setImportError] = useState<string | null>(null)

  const analysis = analyzeCsv(csvData)
  const missing = undefinedVariables(steps, analysis.error ? [] : analysis.columns, authTokenAvailable, scenarioVariables)
  const template = templateColumns(steps, scenarioVariables)
  const rowsCount = analysis.rows.length

  const importFile = async (file: File | undefined) => {
    if (!file) return
    setImportError(null)
    if (!/\.csv$/i.test(file.name) && file.type !== 'text/csv') {
      setImportError('Choisissez un fichier .csv.')
      return
    }
    try {
      onChange(await readCsvFile(file), file.name)
      setOpen(true)
    } catch (err) {
      setImportError(err instanceof Error ? err.message : 'Lecture du fichier impossible.')
    }
  }

  const downloadTemplate = () => {
    const url = URL.createObjectURL(new Blob([csvTemplate(template)], { type: 'text/csv;charset=utf-8' }))
    const a = document.createElement('a')
    a.href = url
    a.download = 'modele-donnees.csv'
    a.click()
    URL.revokeObjectURL(url)
  }

  return (
    <div className="sw-csv">
      <button type="button" className="sw-csv-toggle" onClick={() => setOpen((v) => !v)} aria-expanded={open}>
        <i className="bi bi-table"></i>
        <span>Jeu de données CSV <span className="sw-optional">optionnel</span></span>
        {missing.length > 0 && <span className="sw-tab-badge warn" title="Variables non définies">{missing.length}</span>}
        {!analysis.error && rowsCount > 0 && <span className="sw-tab-badge">{rowsCount}</span>}
        <i className={`bi bi-chevron-${open ? 'up' : 'down'} ms-auto`}></i>
      </button>
      {open && (
        <div className="sw-csv-body">
          <div
            className={`sw-dropzone ${dragOver ? 'over' : ''}`}
            onDragOver={(e) => { e.preventDefault(); setDragOver(true) }}
            onDragLeave={() => setDragOver(false)}
            onDrop={(e) => { e.preventDefault(); setDragOver(false); importFile(e.dataTransfer.files[0]) }}
          >
            <i className="bi bi-file-earmark-arrow-up"></i>
            <span>Glissez un fichier .csv ici ou</span>
            <button type="button" className="sw-chip accent" onClick={() => inputRef.current?.click()}>Importer un fichier</button>
            <input ref={inputRef} type="file" accept=".csv,text/csv" hidden aria-label="Fichier CSV"
              onChange={(e) => { importFile(e.target.files?.[0]); e.target.value = '' }} />
          </div>
          {importError && <div className="sw-field-error">{importError}</div>}

          {fileName && (
            <div className="sw-csv-file">
              <i className="bi bi-file-earmark-spreadsheet"></i>
              <span className="text-truncate">{fileName}</span>
              <button type="button" className="sw-chip" onClick={() => onChange('', null)}>Retirer le fichier</button>
            </div>
          )}

          <div className="sw-field-hint mb-1">
            1ʳᵉ ligne = noms des colonnes (variables <code>${'{'}colonne{'}'}</code>), puis une ligne par utilisateur virtuel. Séparateur « , » ou « ; ».
          </div>
          <textarea
            className={`sw-code ${analysis.error ? 'is-invalid' : ''}`}
            rows={5}
            placeholder={'username,password\nagent001,test123'}
            value={csvData}
            onChange={(e) => onChange(e.target.value, fileName)}
            spellCheck={false}
            aria-label="Données CSV"
          />
          {analysis.error && <div className="sw-field-error">{analysis.error}</div>}

          {!analysis.error && analysis.columns.length > 0 && (
            <div className="sw-csv-preview">
              <div className="sw-field-hint">
                <strong>{rowsCount}</strong> ligne(s) · séparateur « {analysis.separator} » · colonnes :{' '}
                {analysis.columns.map((c) => <code key={c} className="me-1">{'${'}{c}{'}'}</code>)}
              </div>
              <div className="sw-csv-table-wrap">
                <table className="sw-header-table">
                  <thead>
                    <tr>{analysis.columns.map((c) => <th key={c}>{c}</th>)}</tr>
                  </thead>
                  <tbody>
                    {analysis.rows.slice(0, PREVIEW_ROWS).map((row, i) => (
                      <tr key={i}>{row.map((v, j) => <td key={j}>{v}</td>)}</tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {rowsCount > PREVIEW_ROWS && <div className="sw-field-hint">… et {rowsCount - PREVIEW_ROWS} autre(s) ligne(s).</div>}
              <div className="sw-section-label mt-3 mb-1">Distribution des lignes</div>
              <div className="sw-radio-group" role="radiogroup" aria-label="Distribution des lignes du CSV">
                <label>
                  <input type="radio" name="csv-distribution" checked={distribution === 'PER_VIRTUAL_USER'} onChange={() => onDistributionChange('PER_VIRTUAL_USER')} />
                  <span><strong>Une ligne par utilisateur virtuel</strong> — la même ligne pour toutes ses itérations (cyclique s’il y a moins de lignes que d’utilisateurs).</span>
                </label>
                <label>
                  <input type="radio" name="csv-distribution" checked={distribution === 'PER_ITERATION'} onChange={() => onDistributionChange('PER_ITERATION')} />
                  <span><strong>Une nouvelle ligne à chaque itération</strong> — lignes consommées dans l’ordre par tous les utilisateurs, cyclique à la fin du fichier.</span>
                </label>
              </div>
              {distribution === 'PER_VIRTUAL_USER' && virtualUsers != null && rowsCount < virtualUsers && (
                <div className="sw-inline-alert mt-2">
                  <i className="bi bi-arrow-repeat"></i>
                  <span>{rowsCount} ligne(s) pour {virtualUsers} utilisateurs virtuels : les lignes seront réutilisées de façon cyclique (une ligne par utilisateur).</span>
                </div>
              )}
            </div>
          )}

          {missing.length > 0 && (
            <div className="sw-inline-alert warn mt-2">
              <i className="bi bi-exclamation-triangle"></i>
              <span>
                {missing.map((m) => (
                  <span key={m.name} className="d-block">
                    <code>{'${'}{m.name}{'}'}</code> utilisée par l’étape {m.stepIndexes.map((i) => i + 1).join(', ')} mais absente du CSV et des variables connues.
                  </span>
                ))}
              </span>
            </div>
          )}

          <button type="button" className="sw-chip mt-2" onClick={downloadTemplate} disabled={template.length === 0}
            title={template.length === 0 ? 'Aucune variable ${…} à fournir dans les étapes' : `Colonnes : ${template.join(', ')}`}>
            <i className="bi bi-download"></i> Télécharger un modèle CSV
          </button>
        </div>
      )}
    </div>
  )
}

export default CsvPanel
