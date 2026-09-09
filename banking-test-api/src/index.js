// ============================================================
// Banking Test API — serveur HTTP isolé, cible de test pour PERFTEST.
// Node.js + Express + SQLite (node:sqlite), CORS, données 100% fictives.
// Aucun lien avec PERFTEST côté code : PERFTEST l'appelle uniquement via
// de vraies requêtes HTTP (fetch), comme n'importe quelle API cible réelle.
// ============================================================

import express from 'express'
import cors from 'cors'
import { db, nextId, resetDatabase } from './db.js'
import { latencyConfig, rollSimulatedOutcome } from './config.js'

const app = express()

// CORS — PERFTEST tourne dans le navigateur (http://localhost:3000), donc
// toute requête vers ce serveur (http://localhost:8000) est cross-origin.
// cors() gère automatiquement le preflight OPTIONS pour les POST avec
// Content-Type: application/json et Authorization.
app.use(cors())
app.options('*', cors())
app.use(express.json())

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
  const { defaultMinMs, defaultMaxMs } = latencyConfig
  return defaultMinMs + Math.round(Math.random() * (defaultMaxMs - defaultMinMs))
}
function simulateLatency(req) {
  return new Promise((resolve) => setTimeout(resolve, pickLatencyMs(req)))
}

// ------------------------------------------------------------
// Auth — jetons en mémoire (redémarre à vide avec le serveur, pas de
// persistance voulue pour une session de démo). TEST-TOKEN-123 est
// toujours valide : PERFTEST n'a pas de mécanisme pour capturer le token
// d'une étape et l'injecter dans les étapes suivantes du même scénario,
// donc un jeton fixe permet de tester les endpoints protégés sans chaîner
// un vrai login à chaque exécution.
// ------------------------------------------------------------
const tokens = new Map() // token -> user id
const STATIC_TOKEN = 'TEST-TOKEN-123'
tokens.set(STATIC_TOKEN, 'AG001')

function getBearerToken(req) {
  const header = req.headers['authorization'] || ''
  const match = header.match(/^Bearer\s+(.+)$/i)
  return match ? match[1] : null
}
function requireAuth(req, res) {
  const token = getBearerToken(req)
  const userId = token ? tokens.get(token) : undefined
  if (!userId) {
    res.status(401).json({ success: false, error: 'Token invalide ou absent.' })
    return null
  }
  return userId
}

function agentAccountFor(userId) {
  const agent = db.prepare('SELECT * FROM agents WHERE user_id = ?').get(userId)
  return agent ? agent.account_id : 'ACC001'
}

// ============================================================
// GET /health
// ============================================================
app.get('/health', (_req, res) => {
  res.status(200).json({ status: 'ok' })
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
    let id
    try {
      id = nextId('transfer')
      const createdAt = new Date().toISOString()
      db.prepare(
        `INSERT INTO transfers (id, source_account, destination_account, destination_country, amount, currency, kind, status, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`
      ).run(id, sourceAccount, destinationAccount, body.destinationCountry ?? null, Number(amount), body.currency ?? null, kind, 'completed', createdAt)
      db.prepare(
        `INSERT INTO transactions (id, account_id, type, amount, currency, status, date) VALUES (?, ?, ?, ?, ?, ?, ?)`
      ).run(nextId('transaction'), sourceAccount, 'transfer', Number(amount), body.currency ?? 'MAD', 'completed', createdAt.slice(0, 10))
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
  const updatedAt = new Date().toISOString()
  db.prepare(
    `UPDATE transfers SET destination_account = ?, amount = ?, currency = ?, destination_country = ?, updated_at = ? WHERE id = ?`
  ).run(destinationAccount, Number(amount), currency ?? null, destinationCountry ?? null, updatedAt, req.params.id)

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
  const updatedAt = new Date().toISOString()
  db.prepare(
    `UPDATE transfers SET destination_account = ?, amount = ?, currency = ?, destination_country = ?, updated_at = ? WHERE id = ?`
  ).run(destinationAccount, amount, currency, destinationCountry, updatedAt, req.params.id)

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
  db.prepare('DELETE FROM transfers WHERE id = ?').run(req.params.id)
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

  const id = nextId('bill')
  const paidAt = new Date().toISOString()
  db.prepare(
    `INSERT INTO bills (id, client_id, bill_number, amount, status, paid_at) VALUES (?, ?, ?, ?, ?, ?)`
  ).run(id, clientId, billNumber, Number(amount), 'paid', paidAt)

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

  const id = nextId('deposit')
  const createdAt = new Date().toISOString()
  db.prepare(
    `INSERT INTO deposits (id, account_id, amount, status, created_at) VALUES (?, ?, ?, ?, ?)`
  ).run(id, account, Number(amount), 'completed', createdAt)
  db.prepare(`UPDATE accounts SET balance = balance + ? WHERE id = ?`).run(Number(amount), account)

  res.status(201).json({ success: true, depositId: id, account, amount: Number(amount), status: 'completed' })
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

// Port 6000 est délibérément évité : Chrome/Chromium le bloque en dur comme
// "unsafe port" (ancien port X11) et refuse même d'ouvrir la connexion
// (net::ERR_UNSAFE_PORT) — vérifié en conditions réelles : curl fonctionne
// très bien sur 6000, mais AUCUNE requête navigateur ne peut jamais
// l'atteindre. Comme PERFTEST exécute ses fetch() depuis le navigateur,
// ce port aurait rendu tout le Banking Test API inutilisable depuis
// PERFTEST, quel que soit le code écrit ici.
const PORT = process.env.PORT || 8000
app.listen(PORT, () => {
  console.log(`Banking Test API prêt sur http://localhost:${PORT}`)
})
