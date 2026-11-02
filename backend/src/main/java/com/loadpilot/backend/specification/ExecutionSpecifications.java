package com.loadpilot.backend.specification;

import com.loadpilot.backend.dto.request.ExecutionHistoryFilterRequest;
import com.loadpilot.backend.entity.Execution;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/** Filtres de l'historique des executions, tous optionnels et combines en ET. */
public final class ExecutionSpecifications {

    private ExecutionSpecifications() {
    }

    public static Specification<Execution> withFilters(ExecutionHistoryFilterRequest filter) {
        return (root, query, criteriaBuilder) -> {
            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("scenario", JoinType.LEFT).fetch("application", JoinType.LEFT);
                root.fetch("triggeredBy", JoinType.LEFT);
                query.distinct(true);
            }

            List<Predicate> predicates = new ArrayList<>();

            if (filter.status() != null) {
                predicates.add(criteriaBuilder.equal(root.get("status"), filter.status()));
            }
            if (filter.finishedOnly()) {
                predicates.add(root.get("status").in(com.loadpilot.backend.enums.ExecutionStatus.SUCCESS,
                        com.loadpilot.backend.enums.ExecutionStatus.THRESHOLDS_EXCEEDED,
                        com.loadpilot.backend.enums.ExecutionStatus.FAILED, com.loadpilot.backend.enums.ExecutionStatus.CANCELLED));
            }
            if (filter.scenarioId() != null) {
                predicates.add(criteriaBuilder.equal(root.get("scenario").get("id"), filter.scenarioId()));
            }
            if (filter.applicationId() != null) {
                predicates.add(criteriaBuilder.equal(root.get("scenario").get("application").get("id"), filter.applicationId()));
            }
            if (filter.dateFrom() != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("startedAt"), filter.dateFrom()));
            }
            if (filter.dateTo() != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("startedAt"), filter.dateTo()));
            }
            if (filter.search() != null && !filter.search().isBlank()) {
                String pattern = "%" + filter.search().trim().toLowerCase() + "%";
                List<Predicate> searchPredicates = new ArrayList<>();
                searchPredicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("scenario").get("name")), pattern));
                searchPredicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("scenario").get("application").get("name")), pattern));
                // Recherche par identifiant si le texte est un UUID.
                try {
                    UUID asUuid = UUID.fromString(filter.search().trim());
                    searchPredicates.add(criteriaBuilder.equal(root.get("id"), asUuid));
                } catch (IllegalArgumentException ignored) {
                    // Pas un UUID : recherche textuelle uniquement.
                }
                predicates.add(criteriaBuilder.or(searchPredicates.toArray(new Predicate[0])));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
