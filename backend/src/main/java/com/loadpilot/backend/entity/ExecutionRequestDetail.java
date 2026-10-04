package com.loadpilot.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Detail complet d'une requete d'execution (echec ou echantillon, voir RequestDetailRecorder) :
 * requete envoyee et reponse recue, secrets masques, corps limites a 10 Ko. En-tetes et
 * verifications stockes en JSON. Supprime avec sa ligne de resume (ON DELETE CASCADE).
 */
@Entity
@Table(name = "execution_request_detail")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutionRequestDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "result_id", nullable = false, updatable = false, unique = true)
    private ExecutionStepResult result;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id", nullable = false, updatable = false)
    private Execution execution;

    /** FAILURE ou SAMPLE. */
    @Column(name = "kind", nullable = false, length = 10)
    private String kind;

    @Column(name = "request_method", length = 10)
    private String requestMethod;

    @Column(name = "request_url", length = 2048)
    private String requestUrl;

    @Column(name = "request_headers", length = 16000)
    private String requestHeaders;

    @Column(name = "request_body", length = 11000)
    private String requestBody;

    @Column(name = "request_body_truncated", nullable = false)
    private boolean requestBodyTruncated;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_headers", length = 16000)
    private String responseHeaders;

    @Column(name = "response_body", length = 11000)
    private String responseBody;

    @Column(name = "response_body_truncated", nullable = false)
    private boolean responseBodyTruncated;

    @Column(name = "response_size_bytes")
    private Long responseSizeBytes;

    @Column(name = "checks", length = 8000)
    private String checks;
}
