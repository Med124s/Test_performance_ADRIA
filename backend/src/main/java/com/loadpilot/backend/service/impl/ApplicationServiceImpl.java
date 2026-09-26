package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.dto.request.ApplicationRequest;
import com.loadpilot.backend.dto.response.ApplicationResponse;
import com.loadpilot.backend.dto.response.ApplicationTestResponse;
import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.enums.ApplicationStatus;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.exception.ConflictException;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.mapper.ApplicationMapper;
import com.loadpilot.backend.repository.ApplicationRepository;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.loadpilot.backend.security.CurrentUser;
import com.loadpilot.backend.service.AppUserSyncService;
import com.loadpilot.backend.service.ApplicationService;
import com.loadpilot.backend.service.AuditLogService;
import com.loadpilot.backend.service.http.HttpAvailabilityChecker;
import com.loadpilot.backend.service.http.HttpAvailabilityResult;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit (Phase 12) : chaque methode d'ecriture appelle
 * auditLogService.record(...) comme DERNIERE instruction avant de retourner
 * (le flush/delete a deja ete envoye a la DB a ce stade - aucune operation
 * susceptible d'echouer ne suit), ou dans le bloc catch en cas d'echec.
 * record() n'est jamais transactionnel lui-meme et absorbe deja toute
 * exception (voir AuditLogServiceImpl) : cet appel ne peut donc jamais
 * transformer une operation metier reussie en echec, ni l'inverse.
 */
@Service
@RequiredArgsConstructor
public class ApplicationServiceImpl implements ApplicationService {

    private final ApplicationRepository applicationRepository;
    private final ScenarioRepository scenarioRepository;
    private final ApplicationMapper applicationMapper;
    private final AppUserSyncService appUserSyncService;
    private final HttpAvailabilityChecker availabilityChecker;
    private final AuditLogService auditLogService;

    @Value("${app.availability.timeout-seconds:5}")
    private long timeoutSeconds;

    @Override
    @Transactional
    public ApplicationResponse create(ApplicationRequest request, CurrentUser currentUser) {
        try {
            AppUser createdBy = appUserSyncService.sync(currentUser);

            Application application = applicationMapper.toEntity(request);
            application.setCreatedBy(createdBy);
            // Aucun test n'a encore ete lance pour cette application : pas de
            // statut invente (voir ApplicationStatus).

            // saveAndFlush (pas save) : @CreationTimestamp/@UpdateTimestamp ne
            // sont renseignes par Hibernate qu'au moment du flush reel (INSERT
            // execute) - sans flush immediat, la reponse construite juste apres
            // renverrait un createdAt/updatedAt encore nul.
            Application saved = applicationRepository.saveAndFlush(application);
            auditLogService.record(AuditAction.CREATE, AuditModule.APPLICATION, AuditResult.SUCCESS,
                    "Application created: " + saved.getName());
            return applicationMapper.toResponse(saved);
        } catch (RuntimeException e) {
            auditLogService.record(AuditAction.CREATE, AuditModule.APPLICATION, AuditResult.FAILURE,
                    "Application creation failed: " + e.getClass().getSimpleName());
            throw e;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ApplicationResponse getById(UUID id) {
        return applicationMapper.toResponse(findOrThrow(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApplicationResponse> list() {
        List<Application> applications = applicationRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        return applicationMapper.toResponseList(applications);
    }

    @Override
    @Transactional
    public ApplicationResponse update(UUID id, ApplicationRequest request) {
        try {
            Application application = findOrThrow(id);
            applicationMapper.updateEntityFromRequest(request, application);
            Application saved = applicationRepository.saveAndFlush(application);
            auditLogService.record(AuditAction.UPDATE, AuditModule.APPLICATION, AuditResult.SUCCESS,
                    "Application updated: " + saved.getName());
            return applicationMapper.toResponse(saved);
        } catch (RuntimeException e) {
            auditLogService.record(AuditAction.UPDATE, AuditModule.APPLICATION, AuditResult.FAILURE,
                    "Application update failed for id " + id + ": " + e.getClass().getSimpleName());
            throw e;
        }
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        try {
            Application application = findOrThrow(id);
            // Choix assume (Phase 7) : on refuse la suppression plutot que de
            // creer des Scenarios orphelins ou de les supprimer en cascade sans
            // que l'utilisateur ne l'ait explicitement demande.
            if (scenarioRepository.existsByApplicationId(id)) {
                throw new ConflictException(
                        "Impossible de supprimer l'application : des scenarios y sont encore rattaches.");
            }
            applicationRepository.delete(application);
            auditLogService.record(AuditAction.DELETE, AuditModule.APPLICATION, AuditResult.SUCCESS,
                    "Application deleted: " + application.getName());
        } catch (RuntimeException e) {
            auditLogService.record(AuditAction.DELETE, AuditModule.APPLICATION, AuditResult.FAILURE,
                    "Application deletion failed for id " + id + ": " + e.getClass().getSimpleName());
            throw e;
        }
    }

    @Override
    @Transactional
    public ApplicationTestResponse testAvailability(UUID id) {
        try {
            Application application = findOrThrow(id);

            // monitoringUrl n'intervient jamais ici : la cible du test est
            // toujours Application.url (voir Phase 6, section 9).
            HttpAvailabilityResult result = availabilityChecker.check(application.getUrl(), Duration.ofSeconds(timeoutSeconds));

            ApplicationStatus newStatus;
            String message;
            if (!result.success()) {
                newStatus = ApplicationStatus.ERROR;
                message = "Erreur technique lors du test : " + result.errorMessage();
            } else if (result.httpStatus() != null && result.httpStatus() < 400) {
                newStatus = ApplicationStatus.CONNECTED;
                message = "Application joignable (HTTP " + result.httpStatus() + ").";
            } else {
                newStatus = ApplicationStatus.FAILED;
                message = "Application injoignable ou en erreur (HTTP " + result.httpStatus() + ").";
            }

            application.setStatus(newStatus);
            applicationRepository.save(application);

            // Le resultat d'audit (SUCCESS) reflete ici que l'OPERATION de
            // test a bien ete executee, pas que l'application testee est
            // joignable - cette derniere information est deja portee par
            // newStatus/description (voir Phase 12, section 16).
            auditLogService.record(AuditAction.TEST, AuditModule.APPLICATION, AuditResult.SUCCESS,
                    "Application availability tested: " + application.getName() + " -> " + newStatus);
            return new ApplicationTestResponse(newStatus, result.httpStatus(), result.responseTimeMs(), message);
        } catch (RuntimeException e) {
            auditLogService.record(AuditAction.TEST, AuditModule.APPLICATION, AuditResult.FAILURE,
                    "Application availability test failed for id " + id + ": " + e.getClass().getSimpleName());
            throw e;
        }
    }

    private Application findOrThrow(UUID id) {
        return applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Application introuvable : " + id));
    }
}
