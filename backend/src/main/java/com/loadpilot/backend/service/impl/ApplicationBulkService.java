package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.dto.response.BulkActionResponse;
import com.loadpilot.backend.exception.ConflictException;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Actions groupees sur les applications. Chaque element est traite dans SA
 * PROPRE transaction (appel au bean DeletionServiceImpl via son proxy) : un
 * echec (ex. execution en cours) n'annule pas les autres, et la reponse
 * detaille le resultat par application.
 */
@Service
@RequiredArgsConstructor
public class ApplicationBulkService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationBulkService.class);

    private final DeletionServiceImpl deletionService;

    public BulkActionResponse archive(List<UUID> ids) {
        return apply(ids, "Archivée.", deletionService::archiveApplication);
    }

    public BulkActionResponse restore(List<UUID> ids) {
        return apply(ids, "Restaurée.", deletionService::restoreApplication);
    }

    public BulkActionResponse deletePermanently(List<UUID> ids) {
        return apply(ids, "Supprimée définitivement.", deletionService::deleteApplicationPermanently);
    }

    private BulkActionResponse apply(List<UUID> ids, String successMessage, Consumer<UUID> action) {
        List<BulkActionResponse.Item> results = new ArrayList<>();
        for (UUID id : ids.stream().distinct().toList()) {
            String name = null;
            try {
                name = deletionService.findApplication(id).getName();
                action.accept(id);
                results.add(new BulkActionResponse.Item(id.toString(), name, true, successMessage));
            } catch (ConflictException e) {
                results.add(new BulkActionResponse.Item(id.toString(), name, false, e.getMessage()));
            } catch (ResourceNotFoundException e) {
                results.add(new BulkActionResponse.Item(id.toString(), name, false, "Application introuvable (déjà supprimée ?)."));
            } catch (RuntimeException e) {
                log.warn("Action groupee echouee pour l'application {} : {}", id, e.getClass().getSimpleName());
                results.add(new BulkActionResponse.Item(id.toString(), name, false, "Erreur inattendue, réessayez."));
            }
        }
        return BulkActionResponse.of(results);
    }
}
