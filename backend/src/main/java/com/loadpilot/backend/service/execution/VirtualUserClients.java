package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.service.http.HttpClients;
import com.loadpilot.backend.service.http.NetworkProfile;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Clients HTTP d'UN utilisateur virtuel, utilises par son seul thread (jamais partages) : le
 * client qui suit les redirections, cree tout de suite, et celui qui ne les suit pas, cree a la
 * demande. Fermes par shutdownNow() a la fin du VU : sinon leur thread de selecteur, leurs
 * descripteurs et leurs connexions restent ouverts jusqu'au passage du GC.
 *
 * HTTP_1_1 fixe explicitement (P0-A, section 21 - performance du moteur) : sans cela,
 * java.net.http.HttpClient tente une negociation HTTP/2 sur CHAQUE nouvelle connexion, ce qui ajoute
 * jusqu'a ~1s de latence PAR REQUETE contre une cible sans keep-alive. HttpClient fixe sa politique de
 * redirection a la CONSTRUCTION, jamais par requete : d'ou deux clients (NORMAL et NEVER). Pot de
 * cookies commun aux deux, pour qu'une redirection suivie ou non ne perde pas la session.
 */
final class VirtualUserClients {

    private final VirtualUserCookieJar cookies;
    /** Proxy et confiance TLS de l'application testee (voir NetworkProfile), communs aux deux clients. */
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

    /**
     * shutdownNow, pas close() : close() attend la fin des echanges encore actifs - apres une
     * annulation en plein appel, jusqu'au delai d'attente de la requete. Ici, plus aucune reponse
     * n'est attendue. Jamais d'exception : la fin du VU et ses resultats passent avant.
     */
    void shutdownNow() {
        HttpClients.shutdownQuietly(following);
        HttpClients.shutdownQuietly(notFollowing);
    }
}
