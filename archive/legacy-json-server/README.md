# Archive — legacy JSON Server (retiré en P1-O)

Cette archive n'est **PAS** utilisée par l'application. Elle n'est référencée
par aucun code, aucune route, aucun script npm, aucun build. Elle existe
uniquement pour conserver un accès historique aux données de démonstration
qui existaient avant le retrait complet du legacy (P1-O), à un moment où
plusieurs conditions de migration sûre n'étaient pas réunies (voir P1-H
Étape A pour l'analyse complète).

## Contenu

- `db.json` — copie exacte du fichier JSON Server tel qu'il existait avant
  suppression : 1 application, 16 scénarios, 35 steps, 33 exécutions (dont 9
  avec une référence de scénario déjà orpheline dans les données d'origine),
  4 comptes de démonstration (mots de passe en clair, jamais de vrais
  comptes), 3 rôles (catalogue statique, jamais lié à une autorisation
  réelle).
- `server-index.js` — copie du serveur JSON Server programmatique qui
  servait ce fichier (port 4001).

## Pourquoi ces données n'ont pas été migrées vers PostgreSQL

Voir le rapport P1-H (Étape A) pour l'analyse complète, preuves à l'appui :

- Aucune des Applications/Scénarios historiques n'a de propriétaire réel
  attribuable (`created_by` est obligatoire côté PostgreSQL).
- 9 des 33 exécutions référencent un scénario qui n'existe déjà plus dans
  ces données elles-mêmes (incohérence d'origine, indépendante de
  LoadPilot).
- Le statut historique "Avec erreurs" n'a aucun équivalent dans
  `ExecutionStatus` côté backend réel.
- Les 4 comptes de démonstration n'ont jamais eu d'identité Keycloak réelle
  (`AppUser.keycloak_subject` est obligatoire et unique).
- Plusieurs champs (assertions, variables/CSV, `followRedirects`,
  `timeoutMs`, pause/pacing par étape, détail complet requête/réponse HTTP)
  n'ont aucune colonne équivalente dans le modèle PostgreSQL actuel.

Aucune de ces données n'a donc été transformée, fabriquée ou complétée pour
ressembler à des données PostgreSQL réelles — elles restent ici telles
quelles, comme archive historique uniquement.
