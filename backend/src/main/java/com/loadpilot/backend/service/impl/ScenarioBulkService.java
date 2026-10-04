package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.dto.response.BulkActionResponse;
import com.loadpilot.backend.exception.BadRequestException;
import com.loadpilot.backend.exception.ConflictException;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Actions groupees sur les scenarios - meme logique que ApplicationBulkService : chaque
 * scenario est traite dans SA PROPRE transaction (methodes transactionnelles appelees une a
 * une), un echec (ex. execution en cours) n'annule jamais les autres ; le resultat est detaille
 * par scenario (succes / echec + raison). Chaque action unitaire est auditee.
 */
@Service
@RequiredArgsConstructor
public class ScenarioBulkService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioBulkService.class);

    private final DeletionServiceImpl deletionService;
    private final ScenarioStatusService statusService;

    public BulkActionResponse archive(List<UUID> ids) {
        return apply(ids, id -> { deletionService.archiveScenario(id); return "Archivé."; });
    }

    public BulkActionResponse restore(List<UUID> ids) {
        return apply(ids, id -> { deletionService.restoreScenario(id); return "Restauré."; });
    }

    public BulkActionResponse deletePermanently(List<UUID> ids) {
        return apply(ids, id -> { deletionService.deleteScenarioPermanently(id); return "Supprimé définitivement."; });
    }

    public BulkActionResponse activate(List<UUID> ids) {
        return apply(ids, id -> { statusService.activate(id); return "Activé."; });
    }

    public BulkActionResponse deactivate(List<UUID> ids) {
        return apply(ids, id -> { statusService.deactivate(id); return "Désactivé."; });
    }

    public BulkActionResponse resumeSchedules(List<UUID> ids) {
        return apply(ids, id -> {
            ScenarioStatusService.ResumeResult result = statusService.resumeSchedules(id);
            return result.resumed() + " planification(s) réactivée(s)"
                    + (result.expired() > 0 ? ", " + result.expired() + " ponctuelle(s) expirée(s) laissée(s) désactivée(s)" : "") + ".";
        });
    }

    private BulkActionResponse apply(List<UUID> ids, Function<UUID, String> action) {
        List<BulkActionResponse.Item> results = new ArrayList<>();
        for (UUID id : ids.stream().distinct().toList()) {
            String name = null;
            try {
                name = statusService.nameOf(id);
                results.add(new BulkActionResponse.Item(id.toString(), name, true, action.apply(id)));
            } catch (ConflictException | BadRequestException e) {
                results.add(new BulkActionResponse.Item(id.toString(), name, false, e.getMessage()));
            } catch (ResourceNotFoundException e) {
                results.add(new BulkActionResponse.Item(id.toString(), name, false, "Scénario introuvable (déjà supprimé ?)."));
            } catch (RuntimeException e) {
                log.warn("Action groupee echouee pour le scenario {} : {}", id, e.getClass().getSimpleName());
                results.add(new BulkActionResponse.Item(id.toString(), name, false, "Erreur inattendue, réessayez."));
            }
        }
        return BulkActionResponse.of(results);
    }
}
