// ============================================================
// Parsing CSV minimal, sans dépendance externe — lit un vrai fichier CSV
// choisi par l'utilisateur (FileReader, entièrement côté navigateur) et le
// transforme en lignes exploitables comme Variables de test. Aucune valeur
// n'est générée : tout vient du fichier réellement importé.
// ============================================================

export interface ParsedCsv {
  columns: string[]
  rows: Record<string, string>[]
}

/** Découpe une ligne CSV en cellules, en gérant les champs entre guillemets
 * (virgules et guillemets doublés échappés à l'intérieur). */
function splitCsvLine(line: string): string[] {
  const cells: string[] = []
  let current = ''
  let inQuotes = false
  for (let i = 0; i < line.length; i++) {
    const char = line[i]
    if (inQuotes) {
      if (char === '"') {
        if (line[i + 1] === '"') {
          current += '"'
          i++
        } else {
          inQuotes = false
        }
      } else {
        current += char
      }
    } else if (char === '"') {
      inQuotes = true
    } else if (char === ',') {
      cells.push(current.trim())
      current = ''
    } else {
      current += char
    }
  }
  cells.push(current.trim())
  return cells
}

/** Parse le texte brut d'un CSV : première ligne = noms de colonnes
 * (= noms de variables), lignes suivantes = valeurs. Lignes vides ignorées. */
export function parseCsv(text: string): ParsedCsv {
  const lines = text.split(/\r\n|\n|\r/).filter((l) => l.trim().length > 0)
  if (lines.length === 0) return { columns: [], rows: [] }

  const columns = splitCsvLine(lines[0]).filter((c) => c.length > 0)
  const rows = lines.slice(1).map((line) => {
    const cells = splitCsvLine(line)
    const row: Record<string, string> = {}
    columns.forEach((col, i) => {
      row[col] = cells[i] ?? ''
    })
    return row
  })
  return { columns, rows }
}

/** Lit et parse un fichier CSV réellement sélectionné par l'utilisateur. */
export function parseCsvFile(file: File): Promise<ParsedCsv> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(parseCsv(String(reader.result ?? '')))
    reader.onerror = () => reject(reader.error ?? new Error('Impossible de lire le fichier CSV'))
    reader.readAsText(file)
  })
}
