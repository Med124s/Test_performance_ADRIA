package com.loadpilot.backend.specification;

import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.entity.Scenario;
import com.loadpilot.backend.enums.ScenarioStatus;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Filtres de la liste paginee des scenarios - memes vues que ScenarioRepository.findVisible /
 * findArchivedView : "visibles" = scenario ET application non archives ; "archives" = scenario
 * OU application archive.
 */
public final class ScenarioSpecifications {

    private ScenarioSpecifications() {
    }

    public static Specification<Scenario> matching(String search, ScenarioStatus status, UUID applicationId, boolean archived) {
        return (root, query, cb) -> {
            Join<Scenario, Application> app = root.join("application", JoinType.INNER);
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(archived
                    ? cb.or(cb.isTrue(root.get("archived")), cb.isTrue(app.get("archived")))
                    : cb.and(cb.isFalse(root.get("archived")), cb.isFalse(app.get("archived"))));
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (applicationId != null) predicates.add(cb.equal(app.get("id"), applicationId));
            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), pattern),
                        cb.like(cb.lower(app.get("name")), pattern),
                        cb.like(cb.lower(cb.coalesce(root.get("description"), "")), pattern)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
