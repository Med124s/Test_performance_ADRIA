package com.loadpilot.backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Utilise a la fois pour la creation (POST) et la mise a jour (PUT).
 *
 * Champs de charge (P0-A) tous OPTIONNELS (nullable) : un client qui ne les
 * envoie pas obtient le comportement historique a une seule passe (voir
 * ScenarioServiceImpl, qui applique les valeurs par defaut reelles -
 * jamais MapStruct/le champ Java brut, pour ne jamais persister un null
 * dans une colonne NOT NULL). Valides UNIQUEMENT quand presents (@Min sur
 * un Integer null ne declenche jamais de violation).
 */
public record ScenarioRequest(

        @NotNull(message = "L'identifiant de l'application est obligatoire.")
        UUID applicationId,

        @NotBlank(message = "Le nom du scenario est obligatoire.")
        @Size(max = 255, message = "Le nom ne doit pas depasser 255 caracteres.")
        String name,

        @Size(max = 1000, message = "La description ne doit pas depasser 1000 caracteres.")
        String description,

        // P0-B : ce plafond n'est plus la limite operationnelle reelle (voir
        // app.execution.max-virtual-users-per-execution, configurable par
        // environnement et appliquee dans ScenarioServiceImpl) - seulement
        // un garde-fou absolu de bon sens (valeur totalement deraisonnable,
        // quel que soit le deploiement), jamais retire.
        @Min(value = 1, message = "Le nombre d'utilisateurs virtuels doit etre au moins 1.")
        @Max(value = 10000, message = "Le nombre d'utilisateurs virtuels ne doit pas depasser 10000.")
        Integer virtualUsers,

        @Min(value = 0, message = "Le ramp-up ne peut pas etre negatif.")
        Integer rampUpSeconds,

        @Min(value = 1, message = "La duree doit etre d'au moins 1 seconde si fournie.")
        Integer durationSeconds,

        @Min(value = 1, message = "Le nombre d'iterations doit etre d'au moins 1 si fourni.")
        Integer iterations,

        @Min(value = 0, message = "Le think time ne peut pas etre negatif.")
        Integer thinkTimeMs,

        // P1-Q Etape B - donnees CSV optionnelles (premiere ligne = noms de
        // colonnes/variables) : null/absent = comportement historique
        // inchange, aucune variable de donnees (voir Scenario.csvData).
        // Limite de taille volontairement genereuse mais bornee : evite
        // qu'un champ TEXTE illimite cote client ne devienne un vecteur de
        // charge utile non maitrisee sur cette colonne.
        @Size(max = 50000, message = "Les donnees CSV ne doivent pas depasser 50000 caracteres.")
        String csvData,

        // Master prompt final (Lot A) - debit cible optionnel (requetes/s).
        // null = aucun pacing (comportement historique inchange, voir
        // Scenario.targetRps). Rejette explicitement 0 et les valeurs
        // negatives (sens invalide pour un debit cible).
        @Min(value = 1, message = "Le debit cible (RPS) doit etre d'au moins 1 si fourni.")
        Integer targetRps
) {
    /** Compatibilite : construit une requete sans parametre de charge
     * explicite (comportement historique a une seule passe). */
    public ScenarioRequest(UUID applicationId, String name, String description) {
        this(applicationId, name, description, null, null, null, null, null, null, null);
    }
}
