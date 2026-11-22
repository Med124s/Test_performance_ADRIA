// ============================================================
// Banking Test API — serveur HTTP isolé, cible de test pour PERFTEST.
// Node.js + Express + SQLite (node:sqlite), CORS, données 100% fictives.
// Aucun lien avec PERFTEST côté code : PERFTEST l'appelle uniquement via
// de vraies requêtes HTTP (fetch), comme n'importe quelle API cible réelle.
// ============================================================

import express from 'express'
import cors from 'cors'
import promClient from 'prom-client'
import { createHash, randomBytes, timingSafeEqual } from 'node:crypto'
import { db, nextId, resetDatabase, resetTestData } from './db.js'
import { latencyConfig, rollSimulatedOutcome, errorSimulationConfig, capacityConfig, overloadOutcome } from './config.js'

const app = express()

// CORS — PERFTEST tourne dans le navigateur (http://localhost:3000), donc
// toute requête vers ce serveur (http://localhost:8888) est cross-origin.
// cors() gère automatiquement le preflight OPTIONS pour les POST avec
// Content-Type: application/json et Authorization.
app.use(cors())
app.options('*', cors())
app.use(express.json())

// ------------------------------------------------------------
// Capacité simulée (CAPACITY_RPS, voir config.js) : au-delà, l'API ralentit
// puis refuse (503), comme un vrai serveur saturé. Désactivée par défaut.
// ------------------------------------------------------------
const CAPACITY_EXEMPT = new Set(['/health', '/metrics', '/api/admin/reset', '/api/reset'])
const recentArrivals = [] // horodatages (ms) des requêtes de la dernière seconde
app.use(async (req, res, next) => {
  if (!capacityConfig.capacityRps || CAPACITY_EXEMPT.has(req.path)) return next()
  const now = Date.now()
  recentArrivals.push(now)
  while (recentArrivals.length && recentArrivals[0] <= now - 1000) recentArrivals.shift()
  const { extraMs, unavailable } = overloadOutcome(recentArrivals.length, capacityConfig.capacityRps)
  if (unavailable) {
    return res.status(503).json({ success: false, error: 'Service indisponible : capacité dépassée (simulation CAPACITY_RPS).' })
  }
  if (extraMs > 0) await new Promise((resolve) => setTimeout(resolve, extraMs))
  next()
})

// ------------------------------------------------------------
// Latence simulée — configurable via ?latency=<ms> (plafonnée), sinon
// aléatoire réaliste (voir config.js). PERFTEST attend réellement la
// réponse : ce n'est pas une fausse valeur injectée dans responseTimeMs,
// c'est le serveur qui met réellement ce temps à répondre.
// ------------------------------------------------------------
function pickLatencyMs(req) {
  const requested = Number(req.query.latency)
  if (Number.isFinite(requested)) {
    return Math.min(Math.max(requested, 0), latencyConfig.maxAllowedMs)
  }
  const route = latencyConfig.routes[req.path]
  const minMs = route ? route.minMs : latencyConfig.defaultMinMs
  const maxMs = route ? route.maxMs : latencyConfig.defaultMaxMs
  return minMs + Math.round(Math.random() * (maxMs - minMs))
}
function simulateLatency(req) {
  return new Promise((resolve) => setTimeout(resolve, pickLatencyMs(req)))
}

// ------------------------------------------------------------
// Auth — 4 modes acceptés sur toutes les routes protégées, pour tester
// chaque type d'authentification d'Application de LoadPilot :
//   1. Bearer (session)  : jeton obtenu via POST /api/auth/login
//                          (type LoadPilot "Connexion automatique").
//   2. Bearer (fixe)     : TEST-TOKEN-123, toujours valide ("Jeton Bearer").
//   3. Clé API           : DEMO-API-KEY-2026 dans l'en-tête X-API-Key ou le
//                          paramètre ?api_key= ("Clé API").
//   4. Basic             : identifiants d'un utilisateur, ex. agent001 /
//                          test123 ("Basic").
// Les jetons de session sont en mémoire (redémarrent à vide avec le
// serveur, pas de persistance voulue pour une démo).
// ------------------------------------------------------------
const tokens = new Map() // token -> user id
const STATIC_TOKEN = 'TEST-TOKEN-123'
tokens.set(STATIC_TOKEN, 'AG001')
const API_KEYS = new Map([['DEMO-API-KEY-2026', 'AG001']]) // clé -> user id

function getBearerToken(req) {
  const header = req.headers['authorization'] || ''
  const match = header.match(/^Bearer\s+(.+)$/i)
  return match ? match[1] : null
}
function getBasicUserId(req) {
  const match = (req.headers['authorization'] || '').match(/^Basic\s+(.+)$/i)
  if (!match) return undefined
  const decoded = Buffer.from(match[1], 'base64').toString('utf8')
  const separator = decoded.indexOf(':')
  if (separator < 0) return undefined
  const user = db.prepare('SELECT * FROM users WHERE username = ?').get(decoded.slice(0, separator))
  return user && user.password === decoded.slice(separator + 1) ? user.id : undefined
}
/** Identifie l'appelant par l'un des modes acceptés ; null si aucun n'est valide. */
function authenticate(req) {
  const token = getBearerToken(req)
  if (token && tokens.get(token)) return { userId: tokens.get(token), method: 'bearer' }
  const apiKey = req.headers['x-api-key'] || req.query.api_key
  if (apiKey && API_KEYS.get(String(apiKey))) return { userId: API_KEYS.get(String(apiKey)), method: 'api_key' }
  const basicUserId = getBasicUserId(req)
  if (basicUserId) return { userId: basicUserId, method: 'basic' }
  return null
}
const AUTH_REQUIRED_ERROR = 'Authentification requise : Bearer, X-API-Key (ou ?api_key=) ou Basic.'
function requireAuth(req, res) {
  const auth = authenticate(req)
  if (!auth) {
    res.status(401).json({ success: false, error: AUTH_REQUIRED_ERROR })
    return null
  }
  return auth.userId
}

function agentAccountFor(userId) {
  const agent = db.prepare('SELECT * FROM agents WHERE user_id = ?').get(userId)
  return agent ? agent.account_id : 'ACC001'
}

// ------------------------------------------------------------
// Soldes — un virement débite le compte source et crédite le compte
// destinataire (s'il est dans cette banque : un virement international vers
// un IBAN externe ne crédite rien ici). PATCH/PUT ajustent la différence,
// DELETE annule le virement (remboursement). Tout se fait sans `await`
// entre la lecture et l'écriture : node:sqlite est synchrone, donc chaque
// opération est atomique même avec beaucoup de requêtes simultanées.
// ------------------------------------------------------------
const findAccount = (id) => db.prepare('SELECT * FROM accounts WHERE id = ?').get(id)
const addToBalance = (id, delta) => db.prepare('UPDATE accounts SET balance = balance + ? WHERE id = ?').run(delta, id)
const INSUFFICIENT_FUNDS = (account, balance, amount) =>
  `Solde insuffisant sur ${account} : ${balance.toFixed(2)} MAD disponibles, ${Number(amount).toFixed(2)} MAD demandés.`

/** Exécute fn dans une transaction SQL ; annule tout si fn renvoie une erreur ({ status, error }) ou lève. */
function inTransaction(fn) {
  db.exec('BEGIN')
  try {
    const result = fn()
    db.exec(result && result.error ? 'ROLLBACK' : 'COMMIT')
    return result
  } catch (err) {
    db.exec('ROLLBACK')
    throw err
  }
}

/** Retire l'effet d'un virement sur les soldes (source remboursée, destinataire débité). */
function reverseTransfer(transfer) {
  addToBalance(transfer.source_account, transfer.amount)
  if (findAccount(transfer.destination_account)) addToBalance(transfer.destination_account, -transfer.amount)
}

/** Applique un virement aux soldes ; { status, error } si le solde de la source ne suffit pas. */
function applyTransfer(source, destination, amount) {
  const account = findAccount(source)
  if (account.balance < amount) return { status: 422, error: INSUFFICIENT_FUNDS(source, account.balance, amount) }
  addToBalance(source, -amount)
  if (findAccount(destination)) addToBalance(destination, amount)
  return null
}

// ============================================================
// GET /health
// ============================================================
app.get('/health', (_req, res) => {
  res.status(200).json({ status: 'ok' })
})

// ============================================================
// GET /metrics — métriques Prometheus du PROCESSUS Node (prom-client,
// métriques par défaut : process_cpu_seconds_total, process_resident_memory_bytes,
// tas, boucle d'événements...). Lu par LoadPilot pendant une exécution
// (monitoring de l'application « Banking ») pour tracer le CPU et la RAM de
// ce serveur. Sans latence simulée, sans authentification, sans erreur aléatoire.
// ============================================================
promClient.collectDefaultMetrics()
app.get('/metrics', async (_req, res) => {
  res.set('Content-Type', promClient.register.contentType)
  res.end(await promClient.register.metrics())
})

// ============================================================
// GET / — le test de connectivité LoadPilot envoie un GET sur l'URL de
// base de l'Application (http://localhost:8888) : sans cette route, Express
// répond 404 et l'Application passe en FAILED ("Échec").
// ============================================================
app.get('/', (_req, res) => {
  res.status(200).json({ status: 'ok', service: 'banking-test-api' })
})

// ============================================================
// GET /api/auth/verify — endpoint protégé de vérification des identifiants
// (test de connectivité LoadPilot pour Bearer, Clé API et Basic).
// Déterministe : ni latence simulée ni erreur aléatoire. 401 sans
// identifiants ou avec des identifiants faux.
// ============================================================
app.get('/api/auth/verify', (req, res) => {
  const auth = authenticate(req)
  if (!auth) {
    return res.status(401).json({ authenticated: false, error: AUTH_REQUIRED_ERROR })
  }
  res.status(200).json({ authenticated: true, method: auth.method })
})

// ============================================================
// POST /api/auth/login
// ============================================================
app.post('/api/auth/login', async (req, res) => {
  await simulateLatency(req)
  const { username, password } = req.body || {}
  if (!username || !password) {
    return res.status(400).json({ success: false, error: 'username et password sont requis.' })
  }
  const user = db.prepare('SELECT * FROM users WHERE username = ?').get(username)
  if (!user || user.password !== password) {
    return res.status(401).json({ success: false, error: 'Identifiants invalides.' })
  }
  const token = `TOKEN-${Math.random().toString(36).slice(2)}${Date.now().toString(36)}`
  tokens.set(token, user.id)
  res.status(200).json({ success: true, token, user: { id: user.id, role: user.role } })
})

// ============================================================
// GET /api/account/balance
// ============================================================
app.get('/api/account/balance', async (req, res) => {
  await simulateLatency(req)
  const userId = requireAuth(req, res)
  if (!userId) return
  const accountId = agentAccountFor(userId)
  const account = db.prepare('SELECT * FROM accounts WHERE id = ?').get(accountId)
  if (!account) {
    return res.status(404).json({ success: false, error: 'Compte introuvable.' })
  }
  res.status(200).json({
    success: true,
    account: account.id,
    balance: account.balance,
    currency: account.currency,
    commission: account.commission,
  })
})

// ============================================================
// GET /api/agent/history
// ============================================================
app.get('/api/agent/history', async (req, res) => {
  await simulateLatency(req)
  const userId = requireAuth(req, res)
  if (!userId) return
  const accountId = agentAccountFor(userId)
  const rows = db.prepare('SELECT * FROM transactions WHERE account_id = ? ORDER BY date DESC').all(accountId)
  res.status(200).json({
    success: true,
    transactions: rows.map((r) => ({
      id: r.id,
      type: r.type,
      amount: r.amount,
      currency: r.currency,
      status: r.status,
      date: r.date,
    })),
  })
})

// ============================================================
// POST /api/transfers/international | /api/transfers/national
// ============================================================
function makeTransferHandler(kind) {
  return async (req, res) => {
    await simulateLatency(req)
    const userId = requireAuth(req, res)
    if (!userId) return

    const body = req.body || {}
    const { sourceAccount, destinationAccount, amount } = body
    if (!sourceAccount || !destinationAccount || !(Number(amount) > 0)) {
      return res.status(400).json({ success: false, error: 'sourceAccount, destinationAccount et amount (positif) sont requis.' })
    }
    // Déclencheurs déterministes (démo/tests reproductibles), en plus de la
    // simulation aléatoire ci-dessous.
    if (destinationAccount === 'NOTFOUND') {
      return res.status(404).json({ success: false, error: 'Compte destinataire introuvable.' })
    }
    if (destinationAccount === 'LOCKED') {
      return res.status(409).json({ success: false, error: 'Compte destinataire verrouillé.' })
    }
    if (destinationAccount === 'ERROR500') {
      return res.status(500).json({ success: false, error: 'Erreur interne simulée.' })
    }

    const rolled = rollSimulatedOutcome()
    if (rolled === 409) {
      return res.status(409).json({ success: false, error: 'Opération en conflit (simulation).' })
    }
    if (rolled === 500) {
      return res.status(500).json({ success: false, error: 'Erreur interne simulée.' })
    }

    // Écriture SQL protégée : une erreur ici (ex. contrainte UNIQUE) ne doit
    // jamais devenir une exception non gérée dans ce handler async — sans
    // ce try/catch, une erreur synchrone de node:sqlite se transforme en
    // rejet de promesse non intercepté par Express, et Node tue le
    // processus entier (c'est exactement ce qui faisait tomber le port 8000).
    // Règles métier : comptes existants (destinataire dans cette banque pour un virement
    // national), solde suffisant — le virement débite la source et crédite le destinataire.
    if (!findAccount(sourceAccount)) {
      return res.status(404).json({ success: false, error: 'Compte source introuvable.' })
    }
    if (kind === 'national' && !findAccount(destinationAccount)) {
      return res.status(404).json({ success: false, error: 'Compte destinataire introuvable.' })
    }
    if (sourceAccount === destinationAccount) {
      return res.status(400).json({ success: false, error: 'Le compte destinataire doit être différent du compte source.' })
    }

    let id
    try {
      const refused = inTransaction(() => {
        const problem = applyTransfer(sourceAccount, destinationAccount, Number(amount))
        if (problem) return problem
        id = nextId('transfer')
        const createdAt = new Date().toISOString()
        db.prepare(
          `INSERT INTO transfers (id, source_account, destination_account, destination_country, amount, currency, kind, status, created_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`
        ).run(id, sourceAccount, destinationAccount, body.destinationCountry ?? null, Number(amount), body.currency ?? null, kind, 'completed', createdAt)
        db.prepare(
          `INSERT INTO transactions (id, account_id, type, amount, currency, status, date) VALUES (?, ?, ?, ?, ?, ?, ?)`
        ).run(nextId('transaction'), sourceAccount, 'transfer', Number(amount), body.currency ?? 'MAD', 'completed', createdAt.slice(0, 10))
        return null
      })
      if (refused) return res.status(refused.status).json({ success: false, error: refused.error })
    } catch (err) {
      console.error(`[transfers/${kind}] Erreur SQL :`, err.message)
      return res.status(500).json({ success: false, error: "Erreur interne lors de l'enregistrement du transfert." })
    }

    res.status(201).json({
      success: true,
      transferId: id,
      kind,
      sourceAccount,
      destinationAccount,
      amount: Number(amount),
      status: 'completed',
    })
  }
}
app.post('/api/transfers/international', makeTransferHandler('international'))
app.post('/api/transfers/national', makeTransferHandler('national'))

function serializeTransfer(row) {
  return {
    transferId: row.id,
    kind: row.kind,
    sourceAccount: row.source_account,
    destinationAccount: row.destination_account,
    destinationCountry: row.destination_country,
    amount: row.amount,
    currency: row.currency,
    status: row.status,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  }
}

/** Modifie un virement et ses effets sur les soldes ; { status, error } si le solde ne suffit plus (rien n'est modifié). */
function updateTransfer(existing, destinationAccount, amount, currency, destinationCountry) {
  return inTransaction(() => {
    reverseTransfer(existing)
    const problem = applyTransfer(existing.source_account, destinationAccount, amount)
    if (problem) return problem
    db.prepare(
      `UPDATE transfers SET destination_account = ?, amount = ?, currency = ?, destination_country = ?, updated_at = ? WHERE id = ?`
    ).run(destinationAccount, amount, currency, destinationCountry, new Date().toISOString(), existing.id)
    return null
  })
}

// ============================================================
// PUT /api/transfers/:id — remplacement complet d'un transfert existant
// ============================================================
app.put('/api/transfers/:id', async (req, res) => {
  await simulateLatency(req)
  const userId = requireAuth(req, res)
  if (!userId) return

  const existing = db.prepare('SELECT * FROM transfers WHERE id = ?').get(req.params.id)
  if (!existing) {
    return res.status(404).json({ success: false, error: 'Transfert introuvable.' })
  }
  const { destinationAccount, amount, currency, destinationCountry } = req.body || {}
  if (!destinationAccount || !(Number(amount) > 0)) {
    return res.status(400).json({ success: false, error: 'destinationAccount et amount (positif) sont requis.' })
  }
  if (existing.kind === 'national' && !findAccount(destinationAccount)) {
    return res.status(404).json({ success: false, error: 'Compte destinataire introuvable.' })
  }
  const refused = updateTransfer(existing, destinationAccount, Number(amount), currency ?? null, destinationCountry ?? null)
  if (refused) return res.status(refused.status).json({ success: false, error: refused.error })

  const updated = db.prepare('SELECT * FROM transfers WHERE id = ?').get(req.params.id)
  res.status(200).json({ success: true, ...serializeTransfer(updated) })
})

// ============================================================
// PATCH /api/transfers/:id — mise à jour partielle d'un transfert existant
// ============================================================
app.patch('/api/transfers/:id', async (req, res) => {
  await simulateLatency(req)
  const userId = requireAuth(req, res)
  if (!userId) return

  const existing = db.prepare('SELECT * FROM transfers WHERE id = ?').get(req.params.id)
  if (!existing) {
    return res.status(404).json({ success: false, error: 'Transfert introuvable.' })
  }
  const body = req.body || {}
  const amount = body.amount !== undefined ? Number(body.amount) : existing.amount
  if (!(amount > 0)) {
    return res.status(400).json({ success: false, error: 'amount doit être positif.' })
  }
  const destinationAccount = body.destinationAccount ?? existing.destination_account
  const currency = body.currency ?? existing.currency
  const destinationCountry = body.destinationCountry ?? existing.destination_country
  if (existing.kind === 'national' && !findAccount(destinationAccount)) {
    return res.status(404).json({ success: false, error: 'Compte destinataire introuvable.' })
  }
  const refused = updateTransfer(existing, destinationAccount, amount, currency, destinationCountry)
  if (refused) return res.status(refused.status).json({ success: false, error: refused.error })

  const updated = db.prepare('SELECT * FROM transfers WHERE id = ?').get(req.params.id)
  res.status(200).json({ success: true, ...serializeTransfer(updated) })
})

// ============================================================
// DELETE /api/transfers/:id
// ============================================================
app.delete('/api/transfers/:id', async (req, res) => {
  await simulateLatency(req)
  const userId = requireAuth(req, res)
  if (!userId) return

  const existing = db.prepare('SELECT * FROM transfers WHERE id = ?').get(req.params.id)
  if (!existing) {
    return res.status(404).json({ success: false, error: 'Transfert introuvable.' })
  }
  // Annulation : la source est remboursée et le destinataire débité du montant.
  inTransaction(() => {
    reverseTransfer(existing)
    db.prepare('DELETE FROM transfers WHERE id = ?').run(req.params.id)
    return null
  })
  res.status(200).json({ success: true, transferId: req.params.id, status: 'deleted' })
})

// ============================================================
// POST /api/bills/pay
// ============================================================
app.post('/api/bills/pay', async (req, res) => {
  await simulateLatency(req)
  const userId = requireAuth(req, res)
  if (!userId) return

  const { clientId, billNumber, amount } = req.body || {}
  if (!clientId || !billNumber || !(Number(amount) > 0)) {
    return res.status(400).json({ success: false, error: 'clientId, billNumber et amount (positif) sont requis.' })
  }
  if (billNumber === 'NOTFOUND') {
    return res.status(404).json({ success: false, error: 'Facture introuvable.' })
  }

  const rolled = rollSimulatedOutcome()
  if (rolled === 409) {
    return res.status(409).json({ success: false, error: 'Facture déjà payée (simulation).' })
  }
  if (rolled === 500) {
    return res.status(500).json({ success: false, error: 'Erreur interne simulée.' })
  }

  // Règles métier : la facture existe, appartient à ce client, n'est pas déjà payée
  // (sinon 409) et le montant payé est exactement celui de la facture.
  const invoice = db.prepare('SELECT * FROM invoices WHERE bill_number = ?').get(billNumber)
  if (!invoice || invoice.client_id !== clientId) {
    return res.status(404).json({ success: false, error: `Facture ${billNumber} introuvable pour le client ${clientId}.` })
  }
  if (invoice.status === 'paid') {
    return res.status(409).json({ success: false, error: `Facture ${billNumber} déjà payée le ${invoice.paid_at}.` })
  }
  if (Math.abs(invoice.amount - Number(amount)) > 0.001) {
    return res.status(400).json({ success: false, error: `Montant différent de la facture : ${invoice.amount.toFixed(2)} MAD attendus.` })
  }

  const id = nextId('bill')
  const paidAt = new Date().toISOString()
  inTransaction(() => {
    db.prepare(`UPDATE invoices SET status = 'paid', paid_at = ? WHERE bill_number = ?`).run(paidAt, billNumber)
    db.prepare(
      `INSERT INTO bills (id, client_id, bill_number, amount, status, paid_at) VALUES (?, ?, ?, ?, ?, ?)`
    ).run(id, clientId, billNumber, Number(amount), 'paid', paidAt)
    return null
  })

  res.status(201).json({ success: true, paymentId: id, clientId, billNumber, amount: Number(amount), status: 'paid' })
})

// ============================================================
// POST /api/deposits
// ============================================================
app.post('/api/deposits', async (req, res) => {
  await simulateLatency(req)
  const userId = requireAuth(req, res)
  if (!userId) return

  const { account, amount } = req.body || {}
  if (!account || !(Number(amount) > 0)) {
    return res.status(400).json({ success: false, error: 'account et amount (positif) sont requis.' })
  }

  const rolled = rollSimulatedOutcome()
  if (rolled === 409) {
    return res.status(409).json({ success: false, error: 'Versement en conflit (simulation).' })
  }
  if (rolled === 500) {
    return res.status(500).json({ success: false, error: 'Erreur interne simulée.' })
  }

  if (!findAccount(account)) {
    return res.status(404).json({ success: false, error: 'Compte introuvable.' })
  }

  const id = nextId('deposit')
  const createdAt = new Date().toISOString()
  inTransaction(() => {
    db.prepare(
      `INSERT INTO deposits (id, account_id, amount, status, created_at) VALUES (?, ?, ?, ?, ?)`
    ).run(id, account, Number(amount), 'completed', createdAt)
    addToBalance(account, Number(amount))
    db.prepare(
      `INSERT INTO transactions (id, account_id, type, amount, currency, status, date) VALUES (?, ?, ?, ?, ?, ?, ?)`
    ).run(nextId('transaction'), account, 'deposit', Number(amount), 'MAD', 'completed', createdAt.slice(0, 10))
    return null
  })

  res.status(201).json({ success: true, depositId: id, account, amount: Number(amount), status: 'completed' })
})

// ============================================================
// /demo/* — démonstration des 3 types de capture de LoadPilot, enchaînés :
//   1. GET  /demo/accounts                 JSON          -> capture $.data.accounts[0].id
//   2. GET  /demo/accounts/:id/session     en-tête       -> capture X-CSRF-Token (+ cookie DEMO_SESSION)
//   3. GET  /demo/transfer-form            page HTML     -> capture input[name=form_token] (value)
//   4. POST /demo/transfer-form            formulaire    -> réutilise form_token, cookie et compte
// Chaque route VÉRIFIE la valeur reçue : une valeur mal capturée fait échouer l'étape suivante
// (403 / 404 avec la raison), c'est ce qui prouve le chaînage. Authentification comme les autres
// routes (Connexion auto de LoadPilot). Latence simulée, mais jamais d'erreur aléatoire (ERROR_RATE).
// Sessions et jetons en mémoire : expiration 10 min, nombre plafonné (aucune croissance sous charge).
// ============================================================
const DEMO_TTL_MS = 10 * 60 * 1000
const DEMO_MAX_ENTRIES = 100_000
const demoSessions = new Map() // id de session -> { userId, accountId, csrf, expiresAt }
const demoFormTokens = new Map() // jeton -> { sessionId, accountId, expiresAt }

const demoId = (bytes = 16) => randomBytes(bytes).toString('hex')

/** Ajoute une entrée en gardant la table bornée : expirées d'abord, puis les plus anciennes. */
function demoRemember(map, key, value) {
  if (map.size >= DEMO_MAX_ENTRIES) {
    const now = Date.now()
    for (const [k, v] of map) if (v.expiresAt <= now) map.delete(k)
    for (const k of map.keys()) {
      if (map.size < DEMO_MAX_ENTRIES) break
      map.delete(k)
    }
  }
  map.set(key, value)
}

function readCookie(req, name) {
  for (const part of (req.headers.cookie || '').split(';')) {
    const separator = part.indexOf('=')
    if (separator > 0 && part.slice(0, separator).trim() === name) return part.slice(separator + 1).trim()
  }
  return null
}

/** Session de démo valide de l'appelant (cookie DEMO_SESSION), sinon réponse 403 envoyée et null. */
function requireDemoSession(req, res, userId) {
  const sessionId = readCookie(req, 'DEMO_SESSION')
  const session = sessionId && demoSessions.get(sessionId)
  if (!session || session.expiresAt <= Date.now() || session.userId !== userId) {
    res.status(403).json({ success: false, error: 'Session de démo absente ou expirée : cookie DEMO_SESSION manquant ou invalide.' })
    return null
  }
  return { sessionId, session }
}

const escapeHtml = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))

// 1. Comptes de l'agent : le sien en premier (utilisable), un second en lecture seule.
app.get('/demo/accounts', async (req, res) => {
  await simulateLatency(req)
  const userId = requireAuth(req, res)
  if (!userId) return
  const own = findAccount(agentAccountFor(userId))
  const other = db.prepare('SELECT * FROM accounts WHERE id <> ? ORDER BY id LIMIT 1').get(own.id)
  const view = (a, access) => ({ id: a.id, balance: a.balance, currency: a.currency, access })
  res.status(200).json({
    success: true,
    data: { agent: userId, accounts: [view(own, 'gestion'), ...(other ? [view(other, 'lecture')] : [])] },
  })
})

// 2. Ouverture d'une session sur un compte géré : jeton CSRF en en-tête, session en cookie.
app.get('/demo/accounts/:id/session', async (req, res) => {
  await simulateLatency(req)
  const userId = requireAuth(req, res)
  if (!userId) return
  const accountId = req.params.id
  if (!findAccount(accountId)) {
    return res.status(404).json({ success: false, error: `Compte introuvable : ${accountId}.` })
  }
  if (accountId !== agentAccountFor(userId)) {
    return res.status(403).json({ success: false, error: `Compte ${accountId} en lecture seule pour cet agent : session refusée.` })
  }
  const sessionId = demoId()
  const csrf = demoId()
  demoRemember(demoSessions, sessionId, { userId, accountId, csrf, expiresAt: Date.now() + DEMO_TTL_MS })
  res.set('X-CSRF-Token', csrf)
  res.cookie('DEMO_SESSION', sessionId, { path: '/demo', httpOnly: true, sameSite: 'lax', maxAge: DEMO_TTL_MS })
  res.status(200).json({ success: true, account: accountId, message: 'Session ouverte : jeton CSRF dans l’en-tête X-CSRF-Token.' })
})

// 3. Page HTML du formulaire de virement : exige le cookie ET l'en-tête X-CSRF-Token de la session.
app.get('/demo/transfer-form', async (req, res) => {
  await simulateLatency(req)
  const userId = requireAuth(req, res)
  if (!userId) return
  const demo = requireDemoSession(req, res, userId)
  if (!demo) return
  const csrf = req.headers['x-csrf-token']
  if (!csrf || csrf !== demo.session.csrf) {
    return res.status(403).json({ success: false, error: 'Jeton CSRF absent ou invalide : en-tête X-CSRF-Token attendu (celui de l’ouverture de session).' })
  }
  const formToken = demoId()
  demoRemember(demoFormTokens, formToken, { sessionId: demo.sessionId, accountId: demo.session.accountId, expiresAt: Date.now() + DEMO_TTL_MS })
  res.status(200).type('html').send(`<!doctype html>
<html lang="fr">
<head><meta charset="utf-8"><title>Virement — démo</title></head>
<body>
  <h1>Nouveau virement</h1>
  <form id="virement" method="post" action="/demo/transfer-form">
    <input type="hidden" name="form_token" value="${formToken}">
    <label>Compte <input type="text" name="account" value="${escapeHtml(demo.session.accountId)}" readonly></label>
    <label>Montant <input type="number" name="amount" min="1"></label>
    <button type="submit">Valider</button>
  </form>
</body>
</html>`)
})

// 4. Envoi du formulaire (application/x-www-form-urlencoded) : jeton du formulaire à usage unique.
app.post('/demo/transfer-form', express.urlencoded({ extended: false }), async (req, res) => {
  await simulateLatency(req)
  const userId = requireAuth(req, res)
  if (!userId) return
  const demo = requireDemoSession(req, res, userId)
  if (!demo) return
  const { form_token: formToken, account, amount } = req.body || {}
  const issued = formToken && demoFormTokens.get(formToken)
  if (!issued || issued.expiresAt <= Date.now() || issued.sessionId !== demo.sessionId) {
    return res.status(403).json({ success: false, error: 'Jeton du formulaire absent, invalide, expiré ou déjà utilisé (champ form_token).' })
  }
  demoFormTokens.delete(formToken) // usage unique
  if (account !== issued.accountId) {
    return res.status(403).json({ success: false, error: `Compte ${account || '(absent)'} différent de celui du formulaire (${issued.accountId}).` })
  }
  const value = Number(amount)
  if (!Number.isFinite(value) || value <= 0) {
    return res.status(400).json({ success: false, error: 'Montant invalide : nombre strictement positif attendu.' })
  }
  res.status(200).json({ success: true, status: 'accepted', reference: `DEMO-${demoId(4).toUpperCase()}`, account, amount: value })
})

// ============================================================
// POST /api/reset — réinitialise toutes les données de test fictives à leur
// état de départ (aucune authentification requise : c'est un utilitaire de
// test, pas une opération bancaire). Le jeton statique TEST-TOKEN-123 reste
// valide après un reset (voir tokens ci-dessus, jamais vidé par ceci).
// ============================================================
app.post('/api/reset', (_req, res) => {
  resetDatabase()
  res.status(200).json({ success: true, message: 'Données de test réinitialisées.' })
})

// ============================================================
// POST /api/admin/reset — remise à zéro entre deux exécutions de charge :
// vide les virements, paiements, dépôts et mouvements créés par les tests,
// restaure les soldes et les factures impayées, et oublie les sessions en
// mémoire. Utilisateurs, agents et clients ne sont pas touchés.
//
// Protégée, pour ne jamais être appelée par erreur (un scénario LoadPilot ne
// l'atteint pas sans la clé) :
//   - désactivée tant que la variable ADMIN_RESET_KEY n'est pas définie ;
//   - refusée si NODE_ENV=production, même avec la clé ;
//   - en-tête X-Admin-Key égal à ADMIN_RESET_KEY obligatoire (401 sinon).
// La clé n'est jamais journalisée.
// ============================================================
const ADMIN_RESET_KEY = (process.env.ADMIN_RESET_KEY || '').trim()
const IS_PRODUCTION = process.env.NODE_ENV === 'production'

/** Comparaison en temps constant (empreintes de même longueur, quelle que soit la clé reçue). */
function isAdminKey(received) {
  const digest = (value) => createHash('sha256').update(String(value)).digest()
  return timingSafeEqual(digest(received), digest(ADMIN_RESET_KEY))
}

app.post('/api/admin/reset', (req, res) => {
  if (IS_PRODUCTION) {
    return res.status(403).json({ success: false, error: 'Remise à zéro interdite en production (NODE_ENV=production).' })
  }
  if (!ADMIN_RESET_KEY) {
    return res.status(403).json({ success: false, error: 'Remise à zéro désactivée : définir ADMIN_RESET_KEY au démarrage du serveur.' })
  }
  const received = req.headers['x-admin-key']
  if (!received || !isAdminKey(received)) {
    return res.status(401).json({ success: false, error: 'En-tête X-Admin-Key absent ou incorrect.' })
  }
  const started = Date.now()
  const { tablesCleared, tablesRestored, deletedRows } = resetTestData()
  // Sessions en mémoire : jetons de connexion (sauf le jeton fixe) et sessions /demo.
  const sessionsCleared = tokens.size - 1 + demoSessions.size + demoFormTokens.size
  for (const token of tokens.keys()) if (token !== STATIC_TOKEN) tokens.delete(token)
  demoSessions.clear()
  demoFormTokens.clear()
  res.status(200).json({
    status: 'reset_ok',
    tables_cleared: tablesCleared,
    tables_restored: tablesRestored,
    deleted_rows: deletedRows,
    sessions_cleared: sessionsCleared,
    duration_ms: Date.now() - started,
  })
})

// Port 6000 est délibérément évité : Chrome/Chromium le bloque en dur comme
// "unsafe port" (ancien port X11) et refuse même d'ouvrir la connexion
// (net::ERR_UNSAFE_PORT) — vérifié en conditions réelles : curl fonctionne
// très bien sur 6000, mais AUCUNE requête navigateur ne peut jamais
// l'atteindre. Comme PERFTEST exécute ses fetch() depuis le navigateur,
// ce port aurait rendu tout le Banking Test API inutilisable depuis
// PERFTEST, quel que soit le code écrit ici.
const PORT = process.env.PORT || 8888
app.listen(PORT, () => {
  console.log(`Banking Test API prêt sur http://localhost:${PORT}`)
  const pct = (v) => `${Math.round(v * 1000) / 10} %`
  console.log(`Erreurs aléatoires : ${pct(errorSimulationConfig.conflict409Probability)} de 409, `
    + `${pct(errorSimulationConfig.serverError500Probability)} de 500 (réglable avec ERROR_RATE)`)
  console.log(capacityConfig.capacityRps
    ? `Capacité simulée : ${capacityConfig.capacityRps} req/s, puis ralentissement et 503 au-delà du double (CAPACITY_RPS)`
    : 'Capacité simulée : désactivée (CAPACITY_RPS non défini)')
  const resetState = IS_PRODUCTION ? 'interdite (NODE_ENV=production)'
    : ADMIN_RESET_KEY ? 'activée (en-tête X-Admin-Key)' : 'désactivée (ADMIN_RESET_KEY non définie)'
  console.log(`Remise à zéro POST /api/admin/reset : ${resetState}`)
})
