package com.loadpilot.backend.entity;

import com.loadpilot.backend.enums.ApplicationAuthType;
import com.loadpilot.backend.enums.ApplicationStatus;
import com.loadpilot.backend.enums.MonitoringAuthType;
import com.loadpilot.backend.enums.MonitoringType;
import com.loadpilot.backend.security.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** Application testee ; "status" est le resultat du dernier test de connectivite. */
@Entity
@Table(name = "application")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Application {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "url", nullable = false)
    private String url;

    /** null tant qu'aucun test n'a ete lance. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private ApplicationStatus status;

    /** Archivage logique : masquee des listes par defaut, restaurable. */
    @Column(name = "archived", nullable = false)
    @Builder.Default
    private boolean archived = false;

    @Column(name = "archived_at")
    private Instant archivedAt;

    /** Message lisible du dernier test de connectivite (jamais de secret). */
    @Column(name = "status_message")
    private String statusMessage;

    /** Date du dernier test ; null si jamais teste ou modifie depuis. */
    @Column(name = "status_checked_at")
    private Instant statusCheckedAt;

    /** Authentification appliquee aux requetes vers cette application (NONE par defaut). */
    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false)
    @Builder.Default
    private ApplicationAuthType authType = ApplicationAuthType.NONE;

    /** Parametres du type d'authentification, en JSON, chiffres en base. */
    @Column(name = "auth_config")
    @Convert(converter = EncryptedStringConverter.class)
    private String authConfig;

    /** Monitoring optionnel du processus de l'application (CPU / RAM pendant les executions). */
    @Enumerated(EnumType.STRING)
    @Column(name = "monitoring_type", nullable = false)
    @Builder.Default
    private MonitoringType monitoringType = MonitoringType.NONE;

    /** Prometheus : URL complete des metriques (ex : http://hote/metrics) ; Actuator : URL de base (ex : http://hote/actuator). */
    @Column(name = "monitoring_url")
    private String monitoringUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "monitoring_auth_type", nullable = false)
    @Builder.Default
    private MonitoringAuthType monitoringAuthType = MonitoringAuthType.NONE;

    /** Nom de l'en-tete (authentification HEADER). */
    @Column(name = "monitoring_header_name")
    private String monitoringHeaderName;

    /** Jeton ou valeur d'en-tete, chiffre en base. */
    @Column(name = "monitoring_secret")
    @Convert(converter = EncryptedStringConverter.class)
    private String monitoringSecret;

    @Column(name = "monitoring_interval_seconds", nullable = false)
    @Builder.Default
    private int monitoringIntervalSeconds = 5;

    /** Lecture CPU / RAM sans proxy (URL de monitoring souvent interne) ; null = avec le proxy de l'application. */
    @Column(name = "monitoring_without_proxy")
    private Boolean monitoringWithoutProxy;

    /** Proxy pour joindre l'application (voir service.http.NetworkProfile) ; null = INHERIT (proxy global). */
    @Enumerated(EnumType.STRING)
    @Column(name = "proxy_mode")
    private com.loadpilot.backend.enums.ProxyMode proxyMode;

    @Column(name = "proxy_host")
    private String proxyHost;

    @Column(name = "proxy_port")
    private Integer proxyPort;

    @Column(name = "proxy_username")
    private String proxyUsername;

    /** Autorites de certification (PEM) propres a cette application ; null = magasin de la JVM. */
    @Column(name = "tls_ca_certificate", length = 65536)
    private String tlsCaCertificate;

    @Column(name = "proxy_password")
    @Convert(converter = EncryptedStringConverter.class)
    private String proxyPassword;

    /** Utilisateur qui a cree l'application. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private AppUser createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
