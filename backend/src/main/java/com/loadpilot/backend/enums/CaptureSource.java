package com.loadpilot.backend.enums;

/** Ou lire une valeur capturee : JSON (chemin dans le corps) ou HEADER (en-tete de reponse) ; null = JSON. */
public enum CaptureSource {
    JSON,
    HEADER,
    HTML
}
