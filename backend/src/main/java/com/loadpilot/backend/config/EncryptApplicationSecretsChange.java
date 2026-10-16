package com.loadpilot.backend.config;

import com.loadpilot.backend.security.SecretCipher;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import liquibase.change.custom.CustomTaskChange;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import liquibase.exception.SetupException;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;

/**
 * Migration Liquibase (correction P1, changes/033-encrypt-application-secrets.xml) : chiffre les
 * secrets DEJA enregistres en clair (application.auth_config, application.monitoring_secret) avec
 * la meme cle que l'application (SecretCipher, variable LOADPILOT_SECRETS_KEY).
 *
 *   - null et chaine vide restent tels quels ; une valeur deja chiffree n'est jamais touchee
 *     (migration rejouable sans risque).
 *   - La cle n'est jamais un parametre du changeset (elle entrerait dans sa somme de controle) et
 *     aucune valeur n'est journalisee : seul le nombre de valeurs chiffrees est affiche.
 */
public class EncryptApplicationSecretsChange implements CustomTaskChange {

    private static final String[] COLUMNS = {"auth_config", "monitoring_secret"};

    private int encrypted;

    @Override
    public void execute(Database database) throws CustomChangeException {
        JdbcConnection connection = (JdbcConnection) database.getConnection();
        try {
            for (String column : COLUMNS) {
                encrypted += encryptColumn(connection, column);
            }
        } catch (SQLException | liquibase.exception.DatabaseException e) {
            throw new CustomChangeException("Chiffrement des secrets existants impossible (" + e.getClass().getSimpleName() + ").");
        } catch (IllegalStateException e) {
            // Cle absente ou invalide : message explicite, sans aucune valeur.
            throw new CustomChangeException(e.getMessage());
        }
    }

    private int encryptColumn(JdbcConnection connection, String column) throws SQLException, liquibase.exception.DatabaseException {
        record Row(Object id, String value) { }
        List<Row> toEncrypt = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, " + column + " FROM application WHERE " + column + " IS NOT NULL AND " + column + " <> ''");
             ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                String value = rs.getString(2);
                if (!SecretCipher.isEncrypted(value)) toEncrypt.add(new Row(rs.getObject(1), value));
            }
        }
        if (toEncrypt.isEmpty()) return 0;
        SecretCipher cipher = SecretCipher.instance();
        try (PreparedStatement update = connection.prepareStatement("UPDATE application SET " + column + " = ? WHERE id = ?")) {
            for (Row row : toEncrypt) {
                update.setString(1, cipher.encrypt(row.value()));
                update.setObject(2, row.id());
                update.addBatch();
            }
            update.executeBatch();
        }
        return toEncrypt.size();
    }

    @Override
    public String getConfirmationMessage() {
        return encrypted + " secret(s) d'application chiffre(s).";
    }

    @Override
    public void setUp() throws SetupException {
        // Aucun parametre.
    }

    @Override
    public void setFileOpener(ResourceAccessor resourceAccessor) {
        // Non utilise.
    }

    @Override
    public ValidationErrors validate(Database database) {
        return new ValidationErrors();
    }
}
