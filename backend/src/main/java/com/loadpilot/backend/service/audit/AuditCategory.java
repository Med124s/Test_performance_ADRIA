package com.loadpilot.backend.service.audit;

import com.loadpilot.backend.enums.AuditModule;
import java.util.EnumSet;
import java.util.Set;

/** Categories du filtre de la page Journal d'audit, chacune regroupant un ou plusieurs modules. */
public enum AuditCategory {
    APPLICATIONS("Applications", EnumSet.of(AuditModule.APPLICATION)),
    SCENARIOS("Scénarios", EnumSet.of(AuditModule.SCENARIO, AuditModule.STEP)),
    EXECUTIONS("Exécutions", EnumSet.of(AuditModule.EXECUTION, AuditModule.METRIC, AuditModule.DASHBOARD)),
    SCHEDULING("Planification", EnumSet.of(AuditModule.SCHEDULING)),
    USERS("Utilisateurs", EnumSet.of(AuditModule.USER)),
    SECURITY("Sécurité", EnumSet.of(AuditModule.SECURITY, AuditModule.AUTH)),
    SETTINGS("Paramètres", EnumSet.of(AuditModule.SETTINGS, AuditModule.ADMIN));

    private final String label;
    private final Set<AuditModule> modules;

    AuditCategory(String label, Set<AuditModule> modules) {
        this.label = label;
        this.modules = modules;
    }

    public String label() {
        return label;
    }

    public Set<AuditModule> modules() {
        return modules;
    }

    public static AuditCategory of(AuditModule module) {
        for (AuditCategory category : values()) {
            if (category.modules.contains(module)) {
                return category;
            }
        }
        return SETTINGS;
    }
}
