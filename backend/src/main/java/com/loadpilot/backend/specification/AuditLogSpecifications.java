package com.loadpilot.backend.specification;

import com.loadpilot.backend.dto.request.AuditLogFilterRequest;
import com.loadpilot.backend.entity.AuditLog;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/**
 * Construit dynamiquement le WHERE de GET /api/audit-logs a partir des
 * filtres fournis (tous optionnels, combines en ET - un filtre absent
 * n'ajoute simplement aucun predicat). Voir AuditLogRepository
 * (JpaSpecificationExecutor). Tout le filtrage est fait cote serveur.
 */
public final class AuditLogSpecifications {

    private AuditLogSpecifications() {
    }

    public static Specification<AuditLog> withFilters(AuditLogFilterRequest filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (filter.q() != null && !filter.q().isBlank()) {
                String like = "%" + filter.q().trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("username")), like, '\\'),
                        cb.like(cb.lower(root.get("targetLabel")), like, '\\'),
                        cb.like(cb.lower(root.get("description")), like, '\\'),
                        cb.like(cb.lower(root.get("actorSource")), like, '\\')));
            }
            if (filter.actor() != null && !filter.actor().isBlank()) {
                if (AuditLogFilterRequest.SYSTEM_ACTOR.equals(filter.actor())) {
                    predicates.add(cb.equal(root.get("actorType"), "SYSTEM"));
                } else {
                    predicates.add(cb.equal(root.get("username"), filter.actor()));
                    predicates.add(cb.equal(root.get("actorType"), "USER"));
                }
            }
            if (filter.category() != null) {
                predicates.add(root.get("module").in(filter.category().modules()));
            }
            if (filter.userId() != null && !filter.userId().isBlank()) {
                predicates.add(cb.equal(root.get("userId"), filter.userId()));
            }
            if (filter.username() != null && !filter.username().isBlank()) {
                predicates.add(cb.equal(root.get("username"), filter.username()));
            }
            if (filter.action() != null) {
                predicates.add(cb.equal(root.get("action"), filter.action()));
            }
            if (filter.module() != null) {
                predicates.add(cb.equal(root.get("module"), filter.module()));
            }
            if (filter.result() != null) {
                predicates.add(cb.equal(root.get("result"), filter.result()));
            }
            if (filter.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("date"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("date"), filter.to()));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
