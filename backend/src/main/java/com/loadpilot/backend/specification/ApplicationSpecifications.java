package com.loadpilot.backend.specification;

import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.enums.ApplicationStatus;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * WHERE de GET /api/applications pagine (meme pattern qu'ExecutionSpecifications) :
 * archivees ou non, filtre de status optionnel, recherche insensible a la
 * casse sur le nom, l'URL et la description - tous combines en ET.
 */
public final class ApplicationSpecifications {

    private ApplicationSpecifications() {
    }

    /** Filtre de status : tous, jamais testees (status null), ou un status precis. */
    public record StatusFilter(boolean matchAll, boolean onlyUntested, ApplicationStatus status) {
        public static StatusFilter any() {
            return new StatusFilter(true, false, null);
        }

        public static StatusFilter untested() {
            return new StatusFilter(false, true, null);
        }

        public static StatusFilter of(ApplicationStatus status) {
            return new StatusFilter(false, false, status);
        }
    }

    public static Specification<Application> matching(String search, StatusFilter status, boolean archived) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("archived"), archived));
            if (status.onlyUntested()) {
                predicates.add(cb.isNull(root.get("status")));
            } else if (!status.matchAll()) {
                predicates.add(cb.equal(root.get("status"), status.status()));
            }
            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), pattern),
                        cb.like(cb.lower(root.get("url")), pattern),
                        cb.like(cb.lower(cb.coalesce(root.get("description"), "")), pattern)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
