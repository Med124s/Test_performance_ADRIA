package com.loadpilot.backend.specification;

import com.loadpilot.backend.dto.request.ExecutionHistoryFilterRequest;
import com.loadpilot.backend.entity.Execution;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Construit dynamiquement le WHERE de GET /api/executions/history a partir
 * des filtres fournis (meme pattern que AuditLogSpecifications - tous
 * optionnels, combines en ET).
 *
 * "distinct(true) + fetch(scenario/application)" UNIQUEMENT quand la
 * requete n'est pas la requete de comptage (Spring Data execute toujours
 * une requete COUNT separee pour la pagination - fetch y serait invalide et
 * inutile). Scenario/Application sont des relations @ManyToOne (jamais une
 * collection) : ce fetch ne multiplie jamais les lignes, donc reste
 * compatible avec Pageable - evite tout N+1 lors de la construction des
 * ExecutionHistoryResponse (scenarioName/applicationName) pour toute une
 * page de resultats en une seule requete SQL.
 */
public final class ExecutionSpecifications {

    private ExecutionSpecifications() {
    }

    public static Specification<Execution> withFilters(ExecutionHistoryFilterRequest filter) {
        return (root, query, criteriaBuilder) -> {
            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("scenario", JoinType.LEFT).fetch("application", JoinType.LEFT);
                // P1-C : LEFT JOIN FETCH sur triggeredBy (nullable, voir
                // Execution.triggeredBy) - evite un N+1 lors du mapping de
                // triggeredByUsername pour toute une page de resultats.
                root.fetch("triggeredBy", JoinType.LEFT);
                query.distinct(true);
            }

            List<Predicate> predicates = new ArrayList<>();

            if (filter.status() != null) {
                predicates.add(criteriaBuilder.equal(root.get("status"), filter.status()));
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
                // L'id d'une Execution est un UUID (jamais un LIKE sur un
                // type non-texte) : comparaison exacte uniquement si le
                // texte recherche est un UUID syntaxiquement valide - jamais
                // une exception levee pour une recherche textuelle normale.
                try {
                    UUID asUuid = UUID.fromString(filter.search().trim());
                    searchPredicates.add(criteriaBuilder.equal(root.get("id"), asUuid));
                } catch (IllegalArgumentException ignored) {
                    // Pas un UUID valide : recherche textuelle uniquement (scenario/application).
                }
                predicates.add(criteriaBuilder.or(searchPredicates.toArray(new Predicate[0])));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
