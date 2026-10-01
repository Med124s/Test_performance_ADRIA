package com.loadpilot.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

/**
 * Utilise a la fois pour la creation (POST) et la mise a jour (PUT) - memes
 * champs editables dans les deux cas (voir Phase 6).
 */
public record ApplicationRequest(

        @NotBlank(message = "Le nom de l'application est obligatoire.")
        @Size(max = 255, message = "Le nom ne doit pas depasser 255 caracteres.")
        String name,

        @Size(max = 1000, message = "La description ne doit pas depasser 1000 caracteres.")
        String description,

        @NotBlank(message = "L'URL de l'application est obligatoire.")
        @URL(message = "L'URL de l'application n'est pas une URL valide.")
        String url,

        @Size(max = 50, message = "Le type ne doit pas depasser 50 caracteres.")
        String type,

        @Size(max = 50, message = "La methode d'authentification ne doit pas depasser 50 caracteres.")
        String authMethod,

        /** Ecriture seule : null = ne pas modifier le token deja enregistre
         * (voir ApplicationServiceImpl.update) - une chaine vide efface
         * explicitement le token existant. Jamais relu (voir
         * ApplicationResponse). */
        @Size(max = 500, message = "Le token ne doit pas depasser 500 caracteres.")
        String authToken
) {
    /** Compatibilite : ancienne forme sans type/authMethod/authToken (avant
     * le passage produit reel du 2026-09-30). */
    public ApplicationRequest(String name, String description, String url) {
        this(name, description, url, null, null, null);
    }
}
