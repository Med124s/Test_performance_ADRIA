package com.loadpilot.backend.service.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import org.junit.jupiter.api.Test;

class NetworkErrorsTest {

    @Test
    void describe_mapsCommonFailuresToReadableMessages() {
        ConnectException dns = new ConnectException();
        dns.initCause(new UnresolvedAddressException());
        assertThat(NetworkErrors.describe(dns, 5)).isEqualTo("hôte introuvable (DNS)");
        assertThat(NetworkErrors.describe(new IOException(new UnknownHostException("x")), 5)).isEqualTo("hôte introuvable (DNS)");
        assertThat(NetworkErrors.describe(new ConnectException(), 5)).isEqualTo("connexion refusée (serveur arrêté ou port fermé)");
        assertThat(NetworkErrors.describe(new HttpConnectTimeoutException("x"), 5)).isEqualTo("délai de connexion dépassé (5 s)");
    }

    @Test
    void describe_neverEchoesRawExceptionMessage() {
        String message = NetworkErrors.describe(new IOException("GET http://h/x?api_key=SECRET failed"), 5);
        assertThat(message).doesNotContain("SECRET");
    }

    @Test
    void redact_removesQueryStringAndUserInfo() {
        assertThat(NetworkErrors.redact("http://user:pwd@host:8080/p?api_key=SECRET#f")).isEqualTo("http://***@host:8080/p");
        assertThat(NetworkErrors.redact("http://host/p")).isEqualTo("http://host/p");
    }
}
