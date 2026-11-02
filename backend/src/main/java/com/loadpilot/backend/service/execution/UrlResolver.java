package com.loadpilot.backend.service.execution;

/** URL d'une etape : absolue telle quelle, ou relative a l'URL de l'application. */
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
