package com.loadpilot.backend.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Chiffrement des secrets stockes en base (correction P1) : AES-256-GCM, IV aleatoire de 12 octets
 * par valeur, etiquette d'authentification de 128 bits (toute modification du texte chiffre est
 * detectee). Format stocke : « enc:v1: » + Base64(IV + texte chiffre).
 *
 * Cle : 32 octets en Base64, fournie UNIQUEMENT par la variable d'environnement LOADPILOT_SECRETS_KEY
 * (app.secrets.encryption-key dans application.yml). Absente ou invalide : l'application refuse de
 * demarrer, jamais de stockage en clair silencieux. La cle et les valeurs ne sont jamais journalisees
 * ni reprises dans un message d'erreur. Configuration : voir docs/SECRETS.md.
 *
 * Une instance unique est aussi exposee via {@link #instance()} pour les objets crees hors de Spring
 * (convertisseur JPA, migration Liquibase), qui doivent utiliser exactement la meme cle.
 */
@Component
public class SecretCipher {

    public static final String PREFIX = "enc:v1:";
    public static final String ENV_VARIABLE = "LOADPILOT_SECRETS_KEY";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private static volatile SecretCipher instance;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${app.secrets.encryption-key:}") String base64Key) {
        this.key = parseKey(base64Key);
        instance = this;
    }

    /** Instance configuree par Spring (convertisseur JPA, migration) ; erreur claire si absente. */
    public static SecretCipher instance() {
        SecretCipher current = instance;
        if (current == null) {
            throw new IllegalStateException("Chiffrement des secrets non initialise : la variable d'environnement "
                    + ENV_VARIABLE + " doit etre definie (voir docs/SECRETS.md).");
        }
        return current;
    }

    private static SecretKey parseKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("La variable d'environnement " + ENV_VARIABLE + " est absente : LoadPilot refuse de "
                    + "demarrer plutot que de stocker les secrets des applications en clair. Generez une cle de 32 octets en "
                    + "Base64 (voir docs/SECRETS.md).");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("La variable d'environnement " + ENV_VARIABLE + " n'est pas du Base64 valide "
                    + "(voir docs/SECRETS.md).");
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException("La variable d'environnement " + ENV_VARIABLE + " doit contenir exactement 32 octets "
                    + "encodes en Base64 (cle AES-256), " + raw.length + " octet(s) recu(s) (voir docs/SECRETS.md).");
        }
        return new SecretKeySpec(raw, "AES");
    }

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /** null et chaine vide restent tels quels ; une valeur deja chiffree n'est jamais re-chiffree. */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty() || isEncrypted(plain)) return plain;
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Chiffrement d'un secret impossible (" + e.getClass().getSimpleName() + ").");
        }
    }

    /**
     * Valeur en clair. Une valeur sans prefixe (ancienne donnee non encore migree) est rendue telle
     * quelle ; elle sera chiffree au prochain enregistrement. Mauvaise cle ou donnee alteree : erreur
     * explicite, sans la valeur.
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty() || !isEncrypted(stored)) return stored;
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (all.length <= IV_BYTES) throw new IllegalArgumentException("trop court");
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Dechiffrement d'un secret impossible : cle " + ENV_VARIABLE
                    + " differente de celle utilisee a l'enregistrement, ou donnee alteree.");
        }
    }
}
