/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** URL de base du serveur Keycloak, ex: "http://localhost:8081" (sans
   * "/realms/..."). */
  readonly VITE_KEYCLOAK_URL?: string
  readonly VITE_KEYCLOAK_REALM?: string
  readonly VITE_KEYCLOAK_CLIENT_ID?: string
  /** URL de base du backend Spring Boot réel. Défaut "http://localhost:8080"
   * si absente. */
  readonly VITE_SPRING_API_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
