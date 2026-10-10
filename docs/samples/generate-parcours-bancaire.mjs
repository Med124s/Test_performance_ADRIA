// Génère les CSV de démo du scénario « Parcours bancaire » à partir des VRAIES données de
// banking-test-api (lues dans banking-test-api/banking.db, juste après POST /api/reset) :
//   parcours-bancaire-5000.csv      5 000 lignes, mode « une nouvelle ligne à chaque itération »
//   users-login.csv                 les 20 agents, mode « une ligne par utilisateur virtuel »
//   users-login-invalides.csv       10 identifiants faux (test négatif)
//
//   cd banking-test-api ; npm start            (puis POST http://localhost:8888/api/reset)
//   node --no-warnings docs/samples/generate-parcours-bancaire.mjs
//
// Résultat identique à chaque exécution (générateur pseudo-aléatoire à graine fixe).
import { DatabaseSync } from 'node:sqlite'
import { writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const db = new DatabaseSync(path.join(here, '..', '..', 'banking-test-api', 'banking.db'), { readOnly: true })
const ROWS = 5000

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
const random = seededRandom(5000)
const between = (min, max) => min + Math.floor(random() * (max - min + 1))
const shuffle = (list) => {
  for (let i = list.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    [list[i], list[j]] = [list[j], list[i]]
  }
  return list
}

const agents = db.prepare(`SELECT u.username, u.password, g.client_id, g.account_id, a.balance
  FROM agents g JOIN users u ON u.id = g.user_id JOIN accounts a ON a.id = g.account_id ORDER BY u.username`).all()
const unpaid = db.prepare(`SELECT bill_number, client_id, amount FROM invoices WHERE status = 'unpaid' ORDER BY bill_number`).all()
if (agents.length !== 20 || unpaid.length < ROWS) {
  throw new Error(`Données inattendues (${agents.length} agents, ${unpaid.length} factures impayées) : faites d'abord POST /api/reset.`)
}
const billsByClient = new Map(agents.map((a) => [a.client_id, []]))
for (const bill of unpaid) billsByClient.get(bill.client_id)?.push(bill)
for (const list of billsByClient.values()) shuffle(list)

// Lignes : les 20 agents se suivent dans un ordre mélangé à chaque tour de 20 lignes, pour que des
// utilisateurs virtuels simultanés travaillent presque toujours sur des comptes différents.
const rows = []
while (rows.length < ROWS) {
  for (const agent of shuffle([...agents])) {
    const others = agents.filter((a) => a !== agent)
    const bill = billsByClient.get(agent.client_id).pop()
    rows.push({
      username: agent.username,
      password: agent.password,
      clientId: agent.client_id,
      sourceAccount: agent.account_id,
      destinationAccount: others[Math.floor(random() * others.length)].account_id,
      amount: between(10, 500),
      patchAmount: between(10, 500),
      putAmount: between(10, 500),
      billNumber: bill.bill_number,
      billAmount: bill.amount.toFixed(2),
      depositAccount: agent.account_id,
      depositAmount: between(20, 1000),
    })
  }
}

// Contrôle des soldes, cas le plus défavorable : aucun virement n'est jamais annulé (DELETE raté),
// chaque virement débite son plus gros montant (POST, PATCH ou PUT) et les dépôts arrivent après.
const balance = new Map(agents.map((a) => [a.account_id, a.balance]))
let lowest = Infinity
for (const r of rows) {
  const out = Math.max(r.amount, r.patchAmount, r.putAmount)
  balance.set(r.sourceAccount, balance.get(r.sourceAccount) - out)
  lowest = Math.min(lowest, balance.get(r.sourceAccount))
  balance.set(r.destinationAccount, balance.get(r.destinationAccount) + out)
  balance.set(r.depositAccount, balance.get(r.depositAccount) + r.depositAmount)
}
if (lowest < 0) throw new Error(`Solde négatif possible (${lowest}) : montants à revoir.`)
if (new Set(rows.map((r) => r.billNumber)).size !== ROWS) throw new Error('Facture utilisée deux fois.')
if (new Set(rows.map((r) => JSON.stringify(r))).size !== ROWS) throw new Error('Lignes en double.')

const toCsv = (header, lines) => [header.join(','), ...lines.map((l) => header.map((h) => l[h]).join(','))].join('\n') + '\n'
const out = (name, text) => writeFileSync(path.join(here, name), text, 'utf8')

out('parcours-bancaire-5000.csv', toCsv(Object.keys(rows[0]), rows))
out('users-login.csv', toCsv(['username', 'password'], agents))
out('users-login-invalides.csv', toCsv(['username', 'password', 'cas'], [
  { username: 'agent001', password: 'mauvais', cas: 'mauvais mot de passe' },
  { username: 'agent007', password: 'test123', cas: 'mot de passe d’un autre agent' },
  { username: 'agent012', password: 'TEST012', cas: 'mot de passe en majuscules' },
  { username: 'agent020', password: 'test019', cas: 'mot de passe de l’agent voisin' },
  { username: 'agent021', password: 'test021', cas: 'utilisateur inexistant' },
  { username: 'agent000', password: 'test000', cas: 'utilisateur inexistant' },
  { username: 'AGENT001', password: 'test123', cas: 'nom d’utilisateur en majuscules' },
  { username: '', password: 'test123', cas: 'nom d’utilisateur vide' },
  { username: 'agent003', password: '', cas: 'mot de passe vide' },
  { username: '', password: '', cas: 'les deux champs vides' },
]))

console.log(`${rows.length} lignes, ${new Set(rows.map((r) => r.billNumber)).size} factures distinctes,`
  + ` solde le plus bas (cas le plus défavorable) : ${lowest.toFixed(2)} MAD`)
for (const a of agents) process.stdout.write(`${a.username}:${rows.filter((r) => r.username === a.username).length} `)
console.log()
