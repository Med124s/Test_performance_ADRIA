// ============================================================
// Jeu de données CSV d'un scénario — MÊMES règles et messages que le moteur
// (backend : service/execution/CsvDataSource.java) : BOM ignoré, séparateur
// « , » ou « ; » détecté sur la ligne d'en-tête, guillemets RFC 4180, lignes
// vides ignorées, noms de colonnes = variables ${colonne}.
// ============================================================

import type { DraftStep } from './wizardModel'
import { stepVariableNames } from './variablesModel'

export const CSV_MAX_LENGTH = 1_000_000
const COLUMN_NAME = /^[A-Za-z_][A-Za-z0-9_]*$/

export interface CsvAnalysis {
  columns: string[]
  rows: string[][]
  separator: ',' | ';'
  error: string | null
}

interface CsvRecord {
  values: string[]
  line: number
}

export function detectSeparator(text: string): ',' | ';' {
  let commas = 0
  let semicolons = 0
  let quoted = false
  for (const c of text) {
    if (c === '"') quoted = !quoted
    else if (!quoted && (c === '\n' || c === '\r')) break
    else if (!quoted && c === ',') commas++
    else if (!quoted && c === ';') semicolons++
  }
  return semicolons > commas ? ';' : ','
}

function split(text: string, separator: string, records: CsvRecord[]): string | null {
  let values: string[] = []
  let field = ''
  let quoted = false
  let wasQuoted = false
  let line = 1
  let recordLine = 1
  for (let i = 0; i <= text.length; i++) {
    const end = i === text.length
    const c = end ? '\n' : text[i]
    if (quoted) {
      if (end) return `Guillemet non fermé (ligne ${recordLine}).`
      if (c === '"') {
        if (text[i + 1] === '"') {
          field += '"'
          i++
        } else {
          quoted = false
        }
      } else {
        if (c === '\n') line++
        field += c
      }
    } else if (c === '"' && field.trim() === '') {
      quoted = true
      wasQuoted = true
      field = ''
    } else if (c === separator) {
      values.push(wasQuoted ? field : field.trim())
      field = ''
      wasQuoted = false
    } else if (c === '\n' || c === '\r') {
      values.push(wasQuoted ? field : field.trim())
      const blank = values.length === 1 && values[0] === '' && !wasQuoted
      if (!blank) records.push({ values, line: recordLine })
      values = []
      field = ''
      wasQuoted = false
      if (c === '\r' && text[i + 1] === '\n') i++
      line++
      recordLine = line
    } else if (wasQuoted) {
      if (c.trim() !== '') return `Ligne ${recordLine} : caractère inattendu après un guillemet fermant.`
    } else {
      field += c
    }
  }
  return null
}

export function analyzeCsv(csv: string | null | undefined): CsvAnalysis {
  const empty = (error: string | null, separator: ',' | ';' = ','): CsvAnalysis => ({ columns: [], rows: [], separator, error })
  if (!csv || !csv.trim()) return empty(null)
  if (csv.length > CSV_MAX_LENGTH) return empty('Les données CSV ne doivent pas dépasser 1 000 000 caractères.')
  const text = csv.charCodeAt(0) === 0xfeff ? csv.slice(1) : csv
  const separator = detectSeparator(text)
  const records: CsvRecord[] = []
  const splitError = split(text, separator, records)
  if (splitError) return empty(splitError, separator)
  if (records.length === 0) return empty(null, separator)

  const columns = records[0].values
  const seen = new Set<string>()
  for (const column of columns) {
    if (!COLUMN_NAME.test(column)) {
      return empty(column === ''
        ? 'Ligne d’en-tête : une colonne n’a pas de nom.'
        : `Nom de colonne invalide « ${column} » : lettres, chiffres et _ uniquement, sans commencer par un chiffre.`, separator)
    }
    if (seen.has(column)) return empty(`Colonne en double : « ${column} ».`, separator)
    seen.add(column)
  }
  const rows: string[][] = []
  let error: string | null = null
  for (const record of records.slice(1)) {
    if (!error && record.values.length !== columns.length) {
      error = `Ligne ${record.line} : ${record.values.length} colonne(s) au lieu de ${columns.length}.`
    }
    rows.push(record.values)
  }
  if (!error && rows.length === 0) error = 'Ajoutez au moins une ligne de données sous la ligne d’en-tête.'
  return { columns, rows, separator, error }
}

export function validateCsv(csv: string): string | undefined {
  return analyzeCsv(csv).error ?? undefined
}

/** Lecture d'un fichier .csv en UTF-8 (BOM retiré, encodage invalide refusé). */
export async function readCsvFile(file: File): Promise<string> {
  if (file.size > CSV_MAX_LENGTH * 4) throw new Error('Fichier trop volumineux (1 000 000 caractères maximum).')
  const buffer = typeof file.arrayBuffer === 'function'
    ? await file.arrayBuffer()
    : await new Promise<ArrayBuffer>((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(reader.result as ArrayBuffer)
      reader.onerror = () => reject(reader.error ?? new Error('Lecture du fichier impossible.'))
      reader.readAsArrayBuffer(file)
    })
  let text: string
  try {
    text = new TextDecoder('utf-8', { fatal: true }).decode(buffer)
  } catch {
    throw new Error('Le fichier n’est pas encodé en UTF-8 : dans Excel, enregistrez-le au format « CSV UTF-8 ».')
  }
  if (text.charCodeAt(0) === 0xfeff) text = text.slice(1)
  if (text.length > CSV_MAX_LENGTH) throw new Error('Fichier trop volumineux (1 000 000 caractères maximum).')
  return text
}

/** Variables réellement substituées par le moteur dans une étape (URL, chemin, query, en-têtes, corps). */
export function stepVariables(step: DraftStep): string[] {
  return stepVariableNames(step)
}

export interface UndefinedVariable {
  name: string
  stepIndexes: number[]
}

/** Variables utilisées mais ni dans le CSV, ni capturées par une étape PRÉCÉDENTE, ni ${authToken}. */
export function undefinedVariables(steps: DraftStep[], csvColumns: string[], authTokenAvailable: boolean,
  scenarioVariables: string[] = []): UndefinedVariable[] {
  const known = new Set([...csvColumns, ...scenarioVariables])
  if (authTokenAvailable) known.add('authToken')
  const missing = new Map<string, number[]>()
  steps.forEach((step, i) => {
    for (const name of stepVariables(step)) {
      if (!known.has(name)) missing.set(name, [...(missing.get(name) ?? []), i])
    }
    const captured = step.captureVariableName.trim()
    if (captured && step.captureJsonPath.trim()) known.add(captured)
  })
  return Array.from(missing, ([name, stepIndexes]) => ({ name, stepIndexes }))
}

/** Colonnes à fournir par le CSV : variables des étapes non capturées et hors ${authToken}. */
export function templateColumns(steps: DraftStep[], scenarioVariables: string[] = []): string[] {
  const captured = new Set([...steps.filter((s) => s.captureJsonPath.trim()).map((s) => s.captureVariableName.trim()), ...scenarioVariables])
  const names = steps.flatMap(stepVariables).filter((n) => n !== 'authToken' && !captured.has(n) && COLUMN_NAME.test(n))
  return Array.from(new Set(names))
}

/** Modèle téléchargeable : en-tête + une ligne vide à compléter (BOM pour Excel, relu sans souci). */
export function csvTemplate(columns: string[]): string {
  return `﻿${columns.join(',')}\r\n${columns.map(() => '').join(',')}\r\n`
}
