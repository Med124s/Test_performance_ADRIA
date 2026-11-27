# LoadPilot — notes pour Claude

Plateforme de tests de performance (projet de stage). Interface et messages en français.

## Architecture et ports

| Composant | Techno | Port |
|---|---|---|
| `backend/` | Spring Boot 3.3, Java 21, Liquibase (`db/changelog/changes/0NN-*.xml`) | 8080 |
| `perftest-frontend/` | React 18, TypeScript, Vite, Vitest | 3000 |
| PostgreSQL | 17, conteneur `loadpilot-db`, base `loadpilot` | 5432 |
| Keycloak | 26, realm `loadpilot`, thème `loadpilot` (`keycloak/`) | 8081 |
| Mailpit | e-mails de développement (rien n'est délivré) | 1025 SMTP, 8025 web |
| `banking-test-api/` | Node / Express, application cible des tests | 8888 |

- Authentification : JWT Keycloak uniquement. Le backend administre Keycloak via le compte de service `loadpilot-backend-admin`.
- Réglages modifiables depuis l'interface : table `app_setting` (`AppSettingsService`).
- Les tests backend utilisent H2 (`application-test.yml`).

## Lancer et tester

```powershell
docker compose up -d --build              # tout, avec .env à la racine (voir .env.example)
cd backend; mvn spring-boot:run           # backend seul
cd perftest-frontend; npm run dev         # frontend seul
cd banking-test-api; npm start            # API cible
cd backend; mvn -o -q test                # tests backend
cd perftest-frontend; npm test; npm run build   # tests et build frontend
```

## Décisions prises

- **Statuts d'une exécution terminée** :
  - « Réussie » ;
  - « Seuils dépassés » (taux d'erreurs ou P95 au-delà des seuils) ;
  - « Échouée » : réservé à un test qui n'a pas pu se dérouler.
- **Limites de charge sur deux niveaux** :
  - plafond du serveur : variables `LOADPILOT_MAX_*`, jamais modifiable depuis l'interface ;
  - limite applicative : réglée par le Super administrateur dans Paramètres > Système, toujours ≤ plafond (par défaut = plafond).
- **Où s'appliquent ces limites** :
  - mêmes règles dans l'interface et sur le serveur (`LoadRules`, `ExecutionLimitsService`) ;
  - vérifiées à l'enregistrement d'un scénario, au lancement et à la planification ;
  - un scénario existant au-delà d'une limite n'est pas modifié, mais son lancement est refusé.
- **Conservation des résultats** :
  - détail des résultats : 90 jours ;
  - en-têtes et corps des requêtes : 30 jours ;
  - un rapport figé est gardé après la purge ;
  - l'option « Conserver » exclut une exécution de la purge.
- **Mode stress** (`loadMode = STRESS`, « Point de rupture » dans l'interface ; le mode manuel reste inchangé ; démo : `docs/demo/GUIDE_DEMO_STRESS.md`) :
  - aucun réglage de charge saisi : le débit offert monte seul, le réservoir de VUs suit (chaque seconde, le plus grand du besoin de la loi de Little sur le débit de la seconde suivante, +20 %, et du déficit mesuré ; croissance au plus ×2 par seconde, 10 VUs au moins) ;
  - seul réglage, optionnel : la vitesse de montée (1 à 50 req/s par seconde, 1 par défaut), copiée sur l'exécution ; précision du début de la rupture ± 5 × la vitesse ;
  - critère de débit suspendu sur une fenêtre où le réservoir manquait de VUs (limite ou retard sur le besoin) : sans cela, une montée rapide sur une cible saine donnait une fausse rupture ;
  - pas de pause pendant un stress test (elle fausserait la comparaison à la baseline) : il se termine ou s'annule ;
  - montée bornée par les limites applicatives en vigueur (VUs par test, débit cible maximal, durée maximale) ;
  - rupture détectée sans seuil saisi, par comparaison à une baseline ; constantes internes dans `StressSettings`, jamais exposées, sauf la vitesse de montée ;
  - rupture → « Seuils dépassés », motif détaillé, niveau de charge enregistré (VUs, débit, temps de réponse, critère) ;
  - aucune rupture avant les limites → « Réussie », note « aucune rupture détectée jusqu'à X VU / Y req/s » ;
  - LoadPilot saturé avant la cible → « Annulée », motif « Non concluant — LoadPilot a atteint sa propre limite technique avant la cible » ; une anomalie de la cible commencée avant la saturation l'emporte si elle se confirme ;
  - baseline inexploitable (moins de 30 réponses en 30 s à 2 req/s) → « Échouée » ;
  - les 429 et 502/503/504 de la cible comptent comme des refus, au même titre que les timeouts et les connexions refusées ;
  - un stress test tourne seul sur le serveur, ne se planifie pas ; think time des étapes conservé, think time global ignoré.
- **Journal d'audit** : 365 jours ; événements Keycloak : 365 jours.
- **Migration de Keycloak vers PostgreSQL** :
  - validée mais pas encore faite : Keycloak tourne en `start-dev` avec H2 dans son conteneur ;
  - sauvegardes (hors dépôt) : `D:\Adria_Stage\backups\keycloak-2026-10-08`.

## Règles

- **Secrets** : jamais dans Git (`.env`, mots de passe, secret client Keycloak, sauvegardes Keycloak). Toujours passer par des variables d'environnement.
- **Secrets des applications testées** : chiffrés en base (AES-GCM), clé `LOADPILOT_SECRETS_KEY` obligatoire au démarrage (voir `docs/SECRETS.md`). Ne jamais la perdre, ni la journaliser.
- **`/api/reset`** de banking-test-api : jamais dans un scénario de charge, car il remet les données de démo à zéro.
- **Erreurs aléatoires** : banking-test-api renvoie des 409/500 aléatoires (7 % par défaut). Lancer avec `ERROR_RATE=0` pour des résultats sans ces erreurs.
- **Capacité simulée** : sans `CAPACITY_RPS`, banking-test-api ne casse pas sous les plafonds de LoadPilot. Pour montrer une rupture en mode stress : `CAPACITY_RPS=50` (rupture mesurée vers 63 req/s offertes).
- **Suites de tests longues** : la mise en veille du poste fige la JVM et fausse les tests de bout en bout du mode stress ; garder la machine éveillée pendant `mvn -o test`.
- **Données** : ne pas supprimer de données existantes, ni lancer `VACUUM FULL`, sans accord.
