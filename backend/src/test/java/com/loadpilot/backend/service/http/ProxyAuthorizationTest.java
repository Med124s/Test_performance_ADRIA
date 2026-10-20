package com.loadpilot.backend.service.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProxyAuthorizationTest {

    private static final URI BANK = URI.create("https://banque.example.invalid/api/comptes");
    private static final Proxy CORPORATE = new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.local", 3128));

    private static String headerAfter(ProxyAuthorization auth, URI uri) {
        return auth.applyTo(HttpRequest.newBuilder(uri), uri).build().headers().firstValue(ProxyAuthorization.HEADER).orElse(null);
    }

    @Test
    void withoutCredentials_nothingIsEverAdded() {
        assertThat(ProxyAuthorization.none().configured()).isFalse();
        assertThat(headerAfter(ProxyAuthorization.of(null, "x", () -> ProxyAuthorization.fixed(CORPORATE)), BANK)).isNull();
        assertThat(headerAfter(ProxyAuthorization.of("  ", "x", () -> ProxyAuthorization.fixed(CORPORATE)), BANK)).isNull();
    }

    @Test
    void credentials_areSentAsBasic_inUtf8_whenTheRequestGoesThroughTheProxy() {
        ProxyAuthorization auth = ProxyAuthorization.of("agent.lp", "pässwörd:1", () -> ProxyAuthorization.fixed(CORPORATE));
        String expected = "Basic " + Base64.getEncoder().encodeToString("agent.lp:pässwörd:1".getBytes(StandardCharsets.UTF_8));
        assertThat(auth.configured()).isTrue();
        assertThat(headerAfter(auth, BANK)).isEqualTo(expected);
    }

    @Test
    void directAddresses_neverReceiveTheProxyCredentials() {
        ProxyAuthorization direct = ProxyAuthorization.of("agent", "secret", () -> ProxyAuthorization.fixed(Proxy.NO_PROXY));
        assertThat(headerAfter(direct, BANK)).isNull();
        ProxyAuthorization noSelector = ProxyAuthorization.of("agent", "secret", () -> null);
        assertThat(headerAfter(noSelector, BANK)).isNull();
    }

    @Test
    void credentialsAreReadFromTheEnvironmentVariables() {
        Map<String, String> env = Map.of("LOADPILOT_HTTPS_PROXY_USERNAME", "agent", "LOADPILOT_HTTPS_PROXY_PASSWORD", "secret");
        ProxyAuthorization auth = ProxyAuthorization.fromEnvironment(env::get, () -> ProxyAuthorization.fixed(CORPORATE));
        assertThat(headerAfter(auth, BANK)).isEqualTo("Basic " + Base64.getEncoder().encodeToString("agent:secret".getBytes(StandardCharsets.UTF_8)));
        assertThat(ProxyAuthorization.fromEnvironment(name -> null, () -> ProxyAuthorization.fixed(CORPORATE)).configured()).isFalse();
    }
}
