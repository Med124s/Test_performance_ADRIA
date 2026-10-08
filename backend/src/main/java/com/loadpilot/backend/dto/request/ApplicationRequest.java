package com.loadpilot.backend.dto.request;

import jakarta.validation.Valid;
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

        /** Optionnel : absent = aucune authentification a la creation,
         * authentification inchangee a la mise a jour. */
        @Valid
        ApplicationAuthRequest auth,

        /** Optionnel : absent = aucun monitoring a la creation, monitoring inchange a la mise a jour. */
        @Valid
        ApplicationMonitoringRequest monitoring
) {

    /** Compatibilite : Application sans authentification explicite. */
    public ApplicationRequest(String name, String description, String url) {
        this(name, description, url, null, null);
    }

    /** Compatibilite : Application sans monitoring explicite. */
    public ApplicationRequest(String name, String description, String url, ApplicationAuthRequest auth) {
        this(name, description, url, auth, null);
    }
}
