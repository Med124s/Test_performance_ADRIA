package com.loadpilot.backend.service.audit;

/** Un champ modifie : libelle francais, valeur avant, valeur apres (textes deja lisibles, jamais de secret). */
public record AuditChange(String label, String before, String after) {
}
