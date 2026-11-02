package com.loadpilot.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) {
        allowBasicProxyAuthenticationInTunnels();
        SpringApplication.run(BackendApplication.class, args);
    }

    /** Autorise l'authentification Basic dans les tunnels HTTPS d'un proxy (refusee par defaut par Java), avant la creation du premier HttpClient. */
    static void allowBasicProxyAuthenticationInTunnels() {
        if (System.getProperty("jdk.http.auth.tunneling.disabledSchemes") == null) {
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        }
    }
}
