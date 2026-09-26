// ============================================================
// Point d'entrée de "la Fake API" (voir src/services/api/httpClient.ts) —
// remplace l'invocation CLI `json-server --watch db.json` par un serveur
// programmatique équivalent (CRUD généré depuis db.json, même port,
// même comportement).
//
// `--watch` a été retiré volontairement : json-server watchait son PROPRE
// fichier de sortie (db.json), ce qui provoquait un rechargement après
// chaque écriture qu'il faisait lui-même — source de connexions
// réinitialisées (ERR_CONNECTION_RESET) sous plusieurs écritures
// rapprochées. Le router en mémoire est déjà à jour après chaque
// mutation ; aucun processus externe n'édite db.json pendant que ce
// serveur tourne, donc ce watch n'apportait rien.
// ============================================================

import jsonServer from 'json-server'
import path from 'path'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const dbPath = path.join(__dirname, '..', 'db.json')

const server = jsonServer.create()
const router = jsonServer.router(dbPath)
const middlewares = jsonServer.defaults()

server.use(middlewares)
server.use(jsonServer.bodyParser)
server.use(router)

const PORT = process.env.PORT || 4001
server.listen(PORT, () => {
  console.log(`JSON Server prêt sur http://localhost:${PORT}`)
})




