package com.loadpilot.backend.dto.request;

import com.loadpilot.backend.enums.AppRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Nouveau compte : mot de passe defini tout de suite si "password" est renseigne, sinon lien envoye par e-mail. */
public record CreateUserRequest(
        @NotBlank(message = "Le nom d'utilisateur est obligatoire.")
        @Size(min = 3, max = 50, message = "Le nom d'utilisateur doit faire entre 3 et 50 caractères.")
        @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "Le nom d'utilisateur ne peut contenir que des lettres, chiffres, points, tirets et tirets bas.")
        String username,

        @Email(message = "L'e-mail n'est pas valide.")
        @Size(max = 254)
        String email,

        @Size(max = 100, message = "Le prénom ne doit pas dépasser 100 caractères.")
        String firstName,

        @Size(max = 100, message = "Le nom ne doit pas dépasser 100 caractères.")
        String lastName,

        @NotNull(message = "Le rôle est obligatoire.")
        AppRole role,

        @Size(max = 128, message = "Le mot de passe ne doit pas dépasser 128 caractères.")
        String password,

        Boolean temporaryPassword
) {

    public CreateUserRequest(String username, String email, String firstName, String lastName, AppRole role) {
        this(username, email, firstName, lastName, role, null, null);
    }

    public boolean hasPassword() {
        return password != null && !password.isBlank();
    }

    @Override
    public String toString() {
        return "CreateUserRequest[username=" + username + ", email=" + email + ", firstName=" + firstName + ", lastName=" + lastName
                + ", role=" + role + ", password=" + (hasPassword() ? "***" : "none") + ", temporaryPassword=" + temporaryPassword + "]";
    }
}
