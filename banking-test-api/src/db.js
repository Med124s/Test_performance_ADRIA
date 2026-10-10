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

  -- Factures émises (données de démo) : payées une seule fois via POST /api/bills/pay.
  -- La table bills ci-dessus garde, elle, le journal des paiements.
  CREATE TABLE IF NOT EXISTS invoices (
    bill_number TEXT PRIMARY KEY,
    client_id TEXT NOT NULL,
    amount REAL NOT NULL,
    status TEXT NOT NULL,
    paid_at TEXT
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
// Même principe pour le client géré par un agent (agentNNN -> CLNNN).
try {
  db.exec('ALTER TABLE agents ADD COLUMN client_id TEXT')
} catch {
  // Colonne déjà présente — rien à faire.
}

// ============================================================
// Données de démo partagées par le tout premier démarrage (base vide) ET par
// POST /api/reset (voir resetDatabase ci-dessous) — les deux reproduisent
// exactement les mêmes données de départ (aucun hasard : générateur fixe).
//
//   20 agents   agent001 … agent020 (utilisateurs AG001 … AG020)
//   20 clients  CL001 … CL020 — l'agent agentNNN gère le client CLNNN
//   20 comptes  ACC001 … ACC020 — le client CLNNN possède le compte ACCNNN
//               (c'est aussi le compte affiché par GET /api/account/balance de agentNNN)
//   6 000 factures impayées FAC-2026-00001 … FAC-2026-06000, 300 par client
//               (facture k -> client CL((k-1) % 20 + 1)), de 20 à 800 MAD
//
// Mots de passe de démo (fictifs, documentés dans README.md) : agent001 / test123
// (inchangé), puis agentNNN / testNNN.
// ============================================================
export const DEMO_AGENT_COUNT = 20
export const DEMO_BILLS_PER_CLIENT = 300

const pad3 = (n) => String(n).padStart(3, '0')
export const demoPassword = (n) => (n === 1 ? 'test123' : `test${pad3(n)}`)
export const demoBillNumber = (k) => `FAC-2026-${String(k).padStart(5, '0')}`

/** Générateur pseudo-aléatoire fixe (mulberry32) : mêmes montants à chaque reset. */
function seededRandom(seed) {
  let a = seed >>> 0
  return () => {
    a = (a + 0x6d2b79f5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

function seedInitialData() {
  const insertUser = db.prepare(`INSERT INTO users (id, username, password, role) VALUES (?, ?, ?, ?)`)
  const insertClient = db.prepare(`INSERT INTO clients (id, name) VALUES (?, ?)`)
  const insertAccount = db.prepare(`INSERT INTO accounts (id, owner_id, balance, commission, currency) VALUES (?, ?, ?, ?, ?)`)
  const insertAgent = db.prepare(`INSERT INTO agents (id, user_id, name, account_id, client_id) VALUES (?, ?, ?, ?, ?)`)
  const insertInvoice = db.prepare(`INSERT INTO invoices (bill_number, client_id, amount, status, paid_at) VALUES (?, ?, ?, 'unpaid', NULL)`)

  db.exec('BEGIN')
  try {
    for (let n = 1; n <= DEMO_AGENT_COUNT; n++) {
      const id = pad3(n)
      insertUser.run(`AG${id}`, `agent${id}`, demoPassword(n), 'agent')
      insertClient.run(`CL${id}`, n === 1 ? 'Client Démo' : `Client Démo ${id}`)
      // Soldes de 30 000 à 49 000 MAD (ACC001 garde ses 50 centimes historiques).
      insertAccount.run(`ACC${id}`, `CL${id}`, 30000 + ((n * 7) % 20) * 1000 + (n === 1 ? 0.5 : 0), 2.5, 'MAD')
      insertAgent.run(`AG${id}`, `AG${id}`, n === 1 ? 'Agent Démo' : `Agent Démo ${id}`, `ACC${id}`, `CL${id}`)
    }
    const random = seededRandom(2026)
    for (let k = 1; k <= DEMO_AGENT_COUNT * DEMO_BILLS_PER_CLIENT; k++) {
      const amount = Math.round((20 + random() * 780) * 100) / 100
      insertInvoice.run(demoBillNumber(k), `CL${pad3(((k - 1) % DEMO_AGENT_COUNT) + 1)}`, amount)
    }

    db.prepare(
      `INSERT INTO transactions (id, account_id, type, amount, currency, status, date) VALUES (?, ?, ?, ?, ?, ?, ?)`
    ).run('TRX001', 'ACC001', 'transfer', 500, 'MAD', 'completed', '2026-08-10')
    db.prepare(
      `INSERT INTO transactions (id, account_id, type, amount, currency, status, date) VALUES (?, ?, ?, ?, ?, ?, ?)`
    ).run('TRX002', 'ACC001', 'deposit', 1200, 'MAD', 'completed', '2026-08-12')

    // Transfert de démo avec un id fixe (TRF001) : sert de cible toute prête
    // pour les scénarios recommandés PUT/PATCH/DELETE /api/transfers/:id
    // (voir recommendedScenarios.ts côté PERFTEST) sans devoir d'abord chaîner
    // un POST /api/transfers/national réel. Déjà compté dans les soldes ci-dessus.
    db.prepare(
      `INSERT INTO transfers (id, source_account, destination_account, destination_country, amount, currency, kind, status, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`
    ).run('TRF001', 'ACC001', 'ACC002', null, 500, 'MAD', 'national', 'completed', '2026-08-15T09:00:00.000Z')
    db.exec('COMMIT')
  } catch (err) {
    db.exec('ROLLBACK')
    throw err
  }
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
    DELETE FROM invoices;
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
