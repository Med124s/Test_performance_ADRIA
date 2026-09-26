package com.loadpilot.backend.mapper;

import com.loadpilot.backend.dto.response.ProfileResponse;
import com.loadpilot.backend.security.CurrentUser;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ProfileMapper {

    /**
     * P1-D — "timezone" vient d'une SECONDE source (l'AppUser persiste,
     * jamais du Jwt qui n'a aucune notion de ce champ) : MapStruct associe
     * chaque @Mapping "source" au parametre dont le nom correspond
     * (multi-source mapping standard), voir ProfileServiceImpl pour l'appel.
     */
    @Mapping(target = "id", source = "currentUser.subject")
    @Mapping(target = "username", source = "currentUser.username")
    @Mapping(target = "name", source = "currentUser.name")
    @Mapping(target = "email", source = "currentUser.email")
    @Mapping(target = "roles", source = "currentUser.roles")
    @Mapping(target = "timezone", source = "timezone")
    ProfileResponse toProfileResponse(CurrentUser currentUser, String timezone);
}
