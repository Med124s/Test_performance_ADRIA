package com.loadpilot.backend.enums;

/** Proxy utilise pour joindre une application testee (voir service.http.NetworkProfile). */
public enum ProxyMode {
    /** Proxy global du serveur (variables LOADPILOT_HTTPS_PROXY_*), comme avant ; aussi la valeur null. */
    INHERIT,
    /** Jamais de proxy, meme si un proxy global est configure. */
    NONE,
    /** Proxy propre a l'application (hote, port, identifiant optionnel). */
    CUSTOM
}
