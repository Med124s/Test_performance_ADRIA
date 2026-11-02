package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.service.http.HttpClients;
import com.loadpilot.backend.service.http.NetworkProfile;
import java.net.http.HttpClient;
import java.time.Duration;

/** Clients HTTP d'un utilisateur virtuel : un qui suit les redirections, un qui ne les suit pas (cree a la demande). */
final class VirtualUserClients {

    private final VirtualUserCookieJar cookies;
    /** Reglages reseau de l'application testee. */
    private final NetworkProfile network;
    private final long connectTimeoutSeconds;
    private final HttpClient following;
    private HttpClient notFollowing;

    VirtualUserClients(VirtualUserCookieJar cookies, NetworkProfile network, long connectTimeoutSeconds) {
        this.network = network;
        this.cookies = cookies;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.following = build(HttpClient.Redirect.NORMAL);
    }

    NetworkProfile network() {
        return network;
    }

    HttpClient forStep(StepExecutionSpec step) {
        if (!Boolean.FALSE.equals(step.followRedirects())) return following;
        if (notFollowing == null) notFollowing = build(HttpClient.Redirect.NEVER);
        return notFollowing;
    }

    private HttpClient build(HttpClient.Redirect redirect) {
        return network.configure(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .followRedirects(redirect)
                .cookieHandler(cookies))
                .build();
    }

    /** Ferme les deux clients sans attendre les echanges en cours. */
    void shutdownNow() {
        HttpClients.shutdownQuietly(following);
        HttpClients.shutdownQuietly(notFollowing);
    }
}
