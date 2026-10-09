package com.loadpilot.backend.service.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Serveur e-mail de LoadPilot contre un faux serveur SMTP minimal (EHLO, MAIL, RCPT, DATA) : envoi
 * reel, etat « joignable », type Mailpit / SMTP, messages d'erreur francais sans secret.
 */
class MailServiceTest {

    private ServerSocket socket;
    private final List<String> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startSmtp() throws IOException {
        socket = new ServerSocket(0);
        Thread t = new Thread(() -> {
            while (!socket.isClosed()) {
                try (Socket client = socket.accept()) {
                    serve(client);
                } catch (IOException ignored) {
                    // arret du faux serveur
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void serve(Socket client) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(client.getOutputStream(), true);
        out.print("220 faux-smtp\r\n");
        out.flush();
        String line;
        StringBuilder data = null;
        while ((line = in.readLine()) != null) {
            if (data != null) {
                if (line.equals(".")) {
                    received.add(data.toString());
                    data = null;
                    out.print("250 OK\r\n");
                } else {
                    data.append(line).append('\n');
                    continue;
                }
            } else if (line.startsWith("EHLO") || line.startsWith("HELO")) {
                out.print("250 faux-smtp\r\n");
            } else if (line.startsWith("DATA")) {
                data = new StringBuilder();
                out.print("354 go\r\n");
            } else if (line.startsWith("QUIT")) {
                out.print("221 bye\r\n");
                out.flush();
                return;
            } else {
                out.print("250 OK\r\n");
            }
            out.flush();
        }
    }

    @AfterEach
    void stopSmtp() throws IOException {
        socket.close();
    }

    private MailService service(String host, int port, boolean enabled) {
        return new MailService(enabled, host, port, "no-reply@loadpilot.local", "LoadPilot", "", "", false, false, 3000, "http://localhost:3000/");
    }

    @Test
    void send_deliversRealMessage_andServerIsReachable() {
        MailService mail = service("localhost", socket.getLocalPort(), true);
        assertThat(mail.available()).isTrue();
        assertThat(mail.info().reachable()).isTrue();
        mail.send("admin@loadpilot.local", "[LoadPilot] E-mail de test", "Bonjour,\nCeci est un test.");
        assertThat(received).hasSize(1);
        assertThat(received.get(0)).contains("To: admin@loadpilot.local").contains("Subject:").contains("Ceci est un test.");
        assertThat(mail.appUrl()).isEqualTo("http://localhost:3000");
    }

    @Test
    void kind_mailpitOnlyForLocalPort1025() {
        assertThat(service("localhost", 1025, true).kind()).isEqualTo("MAILPIT");
        assertThat(service("mailpit", 1025, true).kind()).isEqualTo("MAILPIT");
        assertThat(service("smtp.exemple.com", 587, true).kind()).isEqualTo("SMTP");
    }

    @Test
    void unreachableOrDisabled_frenchMessages() {
        MailService down = service("localhost", 1, true);
        MailService.MailServerInfo info = down.info();
        assertThat(info.reachable()).isFalse();
        assertThat(info.message()).isEqualTo("Serveur e-mail injoignable.");
        assertThatThrownBy(() -> down.send("a@b.c", "s", "t")).hasMessage("Serveur e-mail injoignable.");

        MailService off = service("localhost", socket.getLocalPort(), false);
        assertThat(off.available()).isFalse();
        assertThat(off.info().message()).isEqualTo("Envoi d’e-mails désactivé sur ce serveur.");
        assertThatThrownBy(() -> off.send("a@b.c", "s", "t")).hasMessage("Envoi d’e-mails désactivé sur ce serveur.");
        assertThatThrownBy(() -> service("localhost", socket.getLocalPort(), true).send(" ", "s", "t"))
                .hasMessage("Aucune adresse e-mail pour ce compte.");
    }
}
