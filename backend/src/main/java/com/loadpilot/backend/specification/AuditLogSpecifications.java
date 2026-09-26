package com.loadpilot.backend.specification;

import com.loadpilot.backend.dto.request.AuditLogFilterRequest;
import com.loadpilot.backend.entity.AuditLog;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * Construit dynamiquement le WHERE de GET /api/audit-logs a partir des
 * filtres fournis (tous optionnels, combines en ET - un filtre absent
 * n'ajoute simplement aucun predicat). Voir AuditLogRepository
 * (JpaSpecificationExecutor).
 */
public final class AuditLogSpecifications {

    private AuditLogSpecifications() {
    }

    public static Specification<AuditLog> withFilters(AuditLogFilterRequest filter) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (filter.userId() != null && !filter.userId().isBlank()) {
                predicates.add(criteriaBuilder.equal(root.get("userId"), filter.userId()));
            }
            if (filter.username() != null && !filter.username().isBlank()) {
                predicates.add(criteriaBuilder.equal(root.get("username"), filter.username()));
            }
            if (filter.action() != null) {
                predicates.add(criteriaBuilder.equal(root.get("action"), filter.action()));
            }
            if (filter.module() != null) {
                predicates.add(criteriaBuilder.equal(root.get("module"), filter.module()));
            }
            if (filter.result() != null) {
                predicates.add(criteriaBuilder.equal(root.get("result"), filter.result()));
            }
            if (filter.from() != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("date"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("date"), filter.to()));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
