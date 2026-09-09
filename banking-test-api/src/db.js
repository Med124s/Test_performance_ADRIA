// ============================================================
// Base SQL du Banking Test API — SQLite via `node:sqlite` (module natif de
// Node, disponible sans dépendance depuis Node 22.5+, aucune compilation
// native requise contrairement à better-sqlite3). Choix expliqué : le but
// de ce serveur est de fournir une vraie cible HTTP pour du performance
// testing, pas de valider un schéma bancaire réel — un fichier SQLite
// unique évite d'installer/administrer un service DB séparé tout en
// restant une vraie base SQL (vraies tables, vraies requêtes, vraies
// contraintes). MySQL redeviendrait pertinent si l'objectif explicite
// devenait de tester le comportement d'une vraie base sous charge
// concurrente (locks, pool de connexions) — ce n'est pas le cas ici.
//
// Toutes les données sont 100% fictives.
// ============================================================

import { DatabaseSync } from 'node:sqlite'
import path from 'path'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const dbFile = path.join(__dirname, '..', 'banking.db')

export const db = new DatabaseSync(dbFile)

db.exec(`
  CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,
    username TEXT UNIQUE NOT NULL,
    password TEXT NOT NULL,
    role TEXT NOT NULL
  );

  CREATE TABLE IF NOT EXISTS agents (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    name TEXT NOT NULL,
    account_id TEXT NOT NULL
  );

  CREATE TABLE IF NOT EXISTS clients (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL
  );

  CREATE TABLE IF NOT EXISTS accounts (
    id TEXT PRIMARY KEY,
    owner_id TEXT NOT NULL,
    balance REAL NOT NULL,
    commission REAL NOT NULL,
    currency TEXT NOT NULL
  );

  CREATE TABLE IF NOT EXISTS transactions (
    id TEXT PRIMARY KEY,
    account_id TEXT NOT NULL,
    type TEXT NOT NULL,
    amount REAL NOT NULL,
    currency TEXT NOT NULL,
    status TEXT NOT NULL,
    date TEXT NOT NULL
  );

  CREATE TABLE IF NOT EXISTS transfers (
    id TEXT PRIMARY KEY,
    source_account TEXT NOT NULL,
    destination_account TEXT NOT NULL,
    destination_country TEXT,
    amount REAL NOT NULL,
    currency TEXT,
    kind TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT
  );

  CREATE TABLE IF NOT EXISTS bills (
    id TEXT PRIMARY KEY,
    client_id TEXT NOT NULL,
    bill_number TEXT NOT NULL,
    amount REAL NOT NULL,
    status TEXT NOT NULL,
    paid_at TEXT NOT NULL
  );

  CREATE TABLE IF NOT EXISTS deposits (
    id TEXT PRIMARY KEY,
    account_id TEXT NOT NULL,
    amount REAL NOT NULL,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL
  );
`)

// Migration douce pour un banking.db déjà créé avant l'ajout de la colonne
// `updated_at` (PUT/PATCH /api/transfers/:id) — `CREATE TABLE IF NOT EXISTS`
// n'altère jamais un schéma déjà existant.
try {
  db.exec('ALTER TABLE transfers ADD COLUMN updated_at TEXT')
} catch {
  // Colonne déjà présente — rien à faire.
}

// Insertions de démo partagées par le tout premier démarrage (base vide) ET
// par POST /api/reset (voir resetDatabase ci-dessous) — les deux doivent
// reproduire exactement les mêmes données de départ.
function seedInitialData() {
  db.prepare(`INSERT INTO users (id, username, password, role) VALUES (?, ?, ?, ?)`)
    .run('AG001', 'agent001', 'test123', 'agent')

  db.prepare(`INSERT INTO accounts (id, owner_id, balance, commission, currency) VALUES (?, ?, ?, ?, ?)`)
    .run('ACC001', 'AG001', 15420.5, 2.5, 'MAD')
  db.prepare(`INSERT INTO accounts (id, owner_id, balance, commission, currency) VALUES (?, ?, ?, ?, ?)`)
    .run('ACC002', 'CL001', 8000, 1.2, 'MAD')

  db.prepare(`INSERT INTO agents (id, user_id, name, account_id) VALUES (?, ?, ?, ?)`)
    .run('AG001', 'AG001', 'Agent Démo', 'ACC001')

  db.prepare(`INSERT INTO clients (id, name) VALUES (?, ?)`).run('CL001', 'Client Démo')

  db.prepare(
    `INSERT INTO transactions (id, account_id, type, amount, currency, status, date) VALUES (?, ?, ?, ?, ?, ?, ?)`
  ).run('TRX001', 'ACC001', 'transfer', 500, 'MAD', 'completed', '2026-08-10')
  db.prepare(
    `INSERT INTO transactions (id, account_id, type, amount, currency, status, date) VALUES (?, ?, ?, ?, ?, ?, ?)`
  ).run('TRX002', 'ACC001', 'deposit', 1200, 'MAD', 'completed', '2026-08-12')

  // Transfert de démo avec un id fixe (TRF001) : sert de cible toute prête
  // pour les scénarios recommandés PUT/PATCH/DELETE /api/transfers/:id
  // (voir recommendedScenarios.ts côté PERFTEST) sans devoir d'abord chaîner
  // un POST /api/transfers/national réel.
  db.prepare(
    `INSERT INTO transfers (id, source_account, destination_account, destination_country, amount, currency, kind, status, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`
  ).run('TRF001', 'ACC001', 'ACC002', null, 500, 'MAD', 'national', 'completed', '2026-08-15T09:00:00.000Z')
}

function seedIfEmpty() {
  const { count } = db.prepare('SELECT COUNT(*) AS count FROM users').get()
  if (count > 0) return
  seedInitialData()
}

seedIfEmpty()

// Compteurs d'ID initialisés à partir du VRAI contenu déjà persisté dans
// banking.db, pas d'une valeur codée en dur — sans ça, chaque redémarrage
// du serveur repart de zéro en mémoire alors que la table, elle, garde ses
// lignes d'avant, et le premier nouvel id généré retombe sur un id déjà
// pris (ex. TRF003 déjà en base → UNIQUE constraint failed sur l'INSERT
// suivant). C'était exactement la cause du crash sur /api/transfers/*.
const ID_TABLES = {
  transfer: { table: 'transfers', prefix: 'TRF' },
  bill: { table: 'bills', prefix: 'BILL' },
  deposit: { table: 'deposits', prefix: 'DEP' },
  transaction: { table: 'transactions', prefix: 'TRX' },
}

function currentMaxSeq(table, prefix) {
  const rows = db.prepare(`SELECT id FROM ${table}`).all()
  let max = 0
  const re = new RegExp(`^${prefix}(\\d+)$`)
  for (const row of rows) {
    const m = re.exec(row.id)
    if (m) max = Math.max(max, parseInt(m[1], 10))
  }
  return max
}

const seq = Object.fromEntries(
  Object.entries(ID_TABLES).map(([key, { table, prefix }]) => [key, currentMaxSeq(table, prefix)])
)

export function nextId(prefix) {
  const { table, prefix: code } = ID_TABLES[prefix]
  // Boucle de sécurité : au cas où un id "TRFxxx" existerait déjà sous une
  // forme que currentMaxSeq n'aurait pas su lire (ex. donnée insérée
  // manuellement avec un format différent), on avance jusqu'à trouver un
  // id réellement libre plutôt que de risquer un second conflit UNIQUE.
  let candidate
  do {
    seq[prefix] += 1
    candidate = `${code}${String(seq[prefix]).padStart(3, '0')}`
  } while (db.prepare(`SELECT 1 FROM ${table} WHERE id = ?`).get(candidate))
  return candidate
}

// ============================================================
// POST /api/reset (voir index.js) — vide entièrement la base fictive puis
// la réensemence avec exactement les mêmes données qu'un tout premier
// démarrage, et réinitialise les compteurs d'ID en conséquence (sans ça,
// les prochains id générés continueraient après les valeurs d'avant le
// reset au lieu de repartir des mêmes id de démo prévisibles).
// ============================================================
export function resetDatabase() {
  db.exec(`
    DELETE FROM transfers;
    DELETE FROM bills;
    DELETE FROM deposits;
    DELETE FROM transactions;
    DELETE FROM accounts;
    DELETE FROM agents;
    DELETE FROM clients;
    DELETE FROM users;
  `)
  seedInitialData()
  for (const [key, { table, prefix }] of Object.entries(ID_TABLES)) {
    seq[key] = currentMaxSeq(table, prefix)
  }
}
