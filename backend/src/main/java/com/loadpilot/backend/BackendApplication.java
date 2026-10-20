package com.loadpilot.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) {
        allowBasicProxyAuthenticationInTunnels();
        SpringApplication.run(BackendApplication.class, args);
    }

    /**
     * Java interdit par defaut l'identification Basic dans les tunnels HTTPS d'un proxy
     * (jdk.http.auth.tunneling.disabledSchemes=Basic) : un proxy avec identifiant repondrait 407.
     * Pose AVANT la premiere creation d'un HttpClient (la valeur est lue une seule fois), quel que soit
     * le lancement (scripts, mvn spring-boot:run, Docker) et meme sans proxy global : un proxy propre a
     * une application (voir NetworkProfile) peut en avoir un. Une valeur passee explicitement (-D) est
     * respectee.
     */
    static void allowBasicProxyAuthenticationInTunnels() {
        if (System.getProperty("jdk.http.auth.tunneling.disabledSchemes") == null) {
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        }
    }
}
