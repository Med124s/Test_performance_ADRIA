package com.loadpilot.backend.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Convertisseur JPA des colonnes secretes : chiffre en ecriture, dechiffre en lecture. */
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
