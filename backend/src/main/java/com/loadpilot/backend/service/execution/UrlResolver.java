package com.loadpilot.backend.service.execution;

/**
 * Resout l'URL REELLE d'un Step : absolue telle quelle, ou relative a
 * l'URL de base de l'Application testee - jamais monitoringUrl. Meme regle
 * que le frontend existant (services/stepRunner.ts resolveStepUrl), reprise
 * ici a l'identique pour rester coherente.
 */
public final class UrlResolver {

    private UrlResolver() {
    }

    public static String resolve(String applicationBaseUrl, String stepUrl) {
        if (stepUrl != null && (stepUrl.startsWith("http://") || stepUrl.startsWith("https://"))) {
            return stepUrl;
        }
        String base = applicationBaseUrl.replaceAll("/+$", "");
        String path = (stepUrl != null && stepUrl.startsWith("/")) ? stepUrl : "/" + (stepUrl == null ? "" : stepUrl);
        return base + path;
    }
}
