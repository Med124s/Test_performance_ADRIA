package com.loadpilot.backend.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Convertisseur JPA des colonnes secretes (correction P1) : la valeur est chiffree en ecriture et
 * dechiffree en lecture, a la frontiere avec la base uniquement. Les services continuent de manipuler
 * la valeur en clair, en memoire, sans aucun changement de comportement.
 *
 * Applique explicitement (@Convert) sur Application.authConfig et Application.monitoringSecret,
 * jamais automatiquement a toutes les chaines.
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return SecretCipher.instance().encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return SecretCipher.instance().decrypt(dbData);
    }
}
