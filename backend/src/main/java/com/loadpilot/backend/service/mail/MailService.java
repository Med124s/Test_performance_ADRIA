package com.loadpilot.backend.service.mail;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Serveur e-mail de LoadPilot (notifications par e-mail, e-mail de test) : SMTP configure par
 * variables d'environnement (LOADPILOT_MAIL_*), Mailpit en developpement (localhost:1025, rien
 * n'est reellement delivre). Distinct du serveur e-mail du systeme d'authentification (mot de
 * passe oublie...). Le mot de passe SMTP n'est jamais renvoye ni journalise.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "mailpit", "host.docker.internal");

    /** Etat du serveur, sans aucun secret. */
    public record MailServerInfo(boolean enabled, String kind, String host, int port, String from, String fromName,
                                 boolean authenticated, boolean reachable, String message) {
    }

    private final boolean enabled;
    private final String host;
    private final int port;
    private final String from;
    private final String fromName;
    private final String username;
    private final String password;
    private final boolean startTls;
    private final boolean ssl;
    private final int timeoutMs;
    private final String appUrl;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mail-sender");
        t.setDaemon(true);
        return t;
    });

    public MailService(@Value("${app.mail.enabled:true}") boolean enabled,
                       @Value("${app.mail.host:localhost}") String host,
                       @Value("${app.mail.port:1025}") int port,
                       @Value("${app.mail.from:no-reply@loadpilot.local}") String from,
                       @Value("${app.mail.from-name:LoadPilot}") String fromName,
                       @Value("${app.mail.username:}") String username,
                       @Value("${app.mail.password:}") String password,
                       @Value("${app.mail.starttls:false}") boolean startTls,
                       @Value("${app.mail.ssl:false}") boolean ssl,
                       @Value("${app.mail.timeout-ms:5000}") int timeoutMs,
                       @Value("${app.mail.app-url:http://localhost:3000}") String appUrl) {
        this.enabled = enabled && host != null && !host.isBlank();
        this.host = host == null ? "" : host.trim();
        this.port = port;
        this.from = from;
        this.fromName = fromName;
        this.username = username == null || username.isBlank() ? null : username;
        this.password = password;
        this.startTls = startTls;
        this.ssl = ssl;
        this.timeoutMs = timeoutMs;
        this.appUrl = appUrl == null ? "" : appUrl.replaceAll("/+$", "");
    }

    /** Envoi possible (serveur configure). */
    public boolean available() {
        return enabled;
    }

    /** Adresse de l'application, pour les liens des e-mails. */
    public String appUrl() {
        return appUrl;
    }

    /** « Mailpit (développement) » pour un serveur local sur le port 1025, sinon « SMTP ». */
    public String kind() {
        return LOCAL_HOSTS.contains(host.toLowerCase()) && port == 1025 ? "MAILPIT" : "SMTP";
    }

    /** Etat du serveur : connexion SMTP reelle (sans envoi). */
    public MailServerInfo info() {
        if (!enabled) {
            return new MailServerInfo(false, kind(), host, port, from, fromName, username != null, false, "Envoi d’e-mails désactivé sur ce serveur.");
        }
        String message = null;
        boolean reachable;
        try (Transport transport = session().getTransport(ssl ? "smtps" : "smtp")) {
            transport.connect(host, port, username, password);
            reachable = true;
        } catch (MessagingException e) {
            reachable = false;
            message = describe(e);
        }
        return new MailServerInfo(true, kind(), host, port, from, fromName, username != null, reachable, message);
    }

    /** Envoi immediat (texte brut) ; leve MailException en cas d'echec (message francais, sans secret). */
    public void send(String to, String subject, String text) {
        if (!enabled) throw new MailException("Envoi d’e-mails désactivé sur ce serveur.");
        if (to == null || to.isBlank()) throw new MailException("Aucune adresse e-mail pour ce compte.");
        try {
            MimeMessage message = new MimeMessage(session());
            message.setFrom(fromName == null || fromName.isBlank() ? new InternetAddress(from)
                    : new InternetAddress(from, fromName, StandardCharsets.UTF_8.name()));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, true));
            message.setSubject(subject, StandardCharsets.UTF_8.name());
            message.setText(text, StandardCharsets.UTF_8.name());
            message.setSentDate(new Date());
            Transport.send(message, username, password);
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new MailException(describe(e));
        }
    }

    /** Envoi en arriere-plan (jamais bloquant pour l'appelant) ; un echec est seulement journalise. */
    public void sendAsync(String to, String subject, String text) {
        if (!enabled) return;
        executor.execute(() -> {
            try {
                send(to, subject, text);
            } catch (MailException e) {
                log.warn("E-mail non envoye : {}", e.getMessage());
            }
        });
    }

    private Session session() {
        Properties props = new Properties();
        String protocol = ssl ? "smtps" : "smtp";
        props.put("mail.transport.protocol", protocol);
        props.put("mail." + protocol + ".host", host);
        props.put("mail." + protocol + ".port", String.valueOf(port));
        props.put("mail." + protocol + ".auth", String.valueOf(username != null));
        props.put("mail." + protocol + ".starttls.enable", String.valueOf(startTls));
        props.put("mail." + protocol + ".connectiontimeout", String.valueOf(timeoutMs));
        props.put("mail." + protocol + ".timeout", String.valueOf(timeoutMs));
        props.put("mail." + protocol + ".writetimeout", String.valueOf(timeoutMs));
        return Session.getInstance(props);
    }

    /** Message francais sans identifiant ni mot de passe. */
    static String describe(Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String name = root.getClass().getSimpleName();
        if (name.contains("ConnectException") || name.contains("UnknownHost") || name.contains("SocketTimeout")) {
            return "Serveur e-mail injoignable.";
        }
        if (e instanceof jakarta.mail.AuthenticationFailedException) {
            return "Le serveur e-mail a refusé l’authentification.";
        }
        if (e instanceof jakarta.mail.internet.AddressException || e instanceof jakarta.mail.SendFailedException) {
            return "Adresse e-mail refusée par le serveur.";
        }
        return "Envoi refusé par le serveur e-mail.";
    }

    /** Echec d'envoi, message deja en francais. */
    public static class MailException extends RuntimeException {
        public MailException(String message) {
            super(message);
        }
    }
}
