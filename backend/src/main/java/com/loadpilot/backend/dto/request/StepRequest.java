package com.loadpilot.backend.dto.request;

import com.loadpilot.backend.enums.HttpMethod;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Utilise a la fois pour la creation (POST) et la mise a jour (PUT).
 *
 * "status" est volontairement ABSENT de ce DTO - meme choix que
 * ScenarioRequest (Phase 7) pour rester coherent : le statut est gere par le
 * service (ACTIVE par defaut a la creation), pas modifiable via ce endpoint
 * pour l'instant.
 *
 * "url" n'utilise PAS @URL (qui exige une URL absolue) : comme cote
 * frontend existant, l'URL d'un Step est le plus souvent un CHEMIN RELATIF
 * a l'URL de l'Application (ex: "/api/auth/login"), et non une URL absolue -
 * imposer @URL casserait ce cas d'usage legitime. Seule l'absence d'espace
 * est verifiee (@Pattern), coherent avec la validation deja utilisee cote
 * frontend pour ce meme champ.
 */
public record StepRequest(

        @NotNull(message = "L'identifiant du scenario est obligatoire.")
        UUID scenarioId,

        @NotBlank(message = "Le nom de l'etape est obligatoire.")
        @Size(max = 255, message = "Le nom ne doit pas depasser 255 caracteres.")
        String name,

        @NotNull(message = "La methode HTTP est obligatoire.")
        HttpMethod method,

        @NotBlank(message = "La ressource (URL) est obligatoire.")
        @Pattern(regexp = "\\S+", message = "La ressource ne doit pas contenir d'espace.")
        String url,

        String headers,

        String body,

        @NotNull(message = "L'ordre de l'etape est obligatoire.")
        @Positive(message = "L'ordre doit etre strictement positif.")
        Integer order,

        @Min(value = 100, message = "Le code HTTP attendu doit etre compris entre 100 et 599.")
        @Max(value = 599, message = "Le code HTTP attendu doit etre compris entre 100 et 599.")
        Integer expectedStatus,

        // P1-Q Etape B - toutes OPTIONNELLES (nullable), memes conventions
        // que les champs de charge de ScenarioRequest : un client qui ne les
        // envoie pas obtient le comportement historique inchange (voir
        // Step.thinkTimeMs/timeoutSeconds/followRedirects/
        // assertionBodyContains, HttpClientExecutionEngine).
        @Min(value = 0, message = "Le think time ne peut pas etre negatif.")
        Integer thinkTimeMs,

        @Min(value = 1, message = "Le timeout doit etre d'au moins 1 seconde si fourni.")
        Integer timeoutSeconds,

        Boolean followRedirects,

        @Size(max = 500, message = "L'assertion ne doit pas depasser 500 caracteres.")
        String assertionBodyContains,

        // Master prompt final (Lot B) - capture de variable dynamique
        // depuis la reponse de CETTE etape, disponible pour les etapes
        // SUIVANTES du MEME utilisateur virtuel uniquement. N'a d'effet
        // que si les DEUX champs sont renseignes (capture explicitement
        // configuree, jamais automatique) - voir Step.captureVariableName/
        // captureJsonPath, JsonPathExtractor.
        @Size(max = 255, message = "Le nom de variable ne doit pas depasser 255 caracteres.")
        String captureVariableName,

        @Size(max = 500, message = "Le chemin de capture ne doit pas depasser 500 caracteres.")
        String captureJsonPath
) {
}
