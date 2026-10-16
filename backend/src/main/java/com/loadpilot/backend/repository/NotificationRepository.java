package com.loadpilot.backend.repository;

import com.loadpilot.backend.entity.Notification;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Page<Notification> findByRecipientIdOrderByCreatedAtDesc(UUID recipientId, Pageable pageable);

    Page<Notification> findByRecipientIdAndReadOrderByCreatedAtDesc(UUID recipientId, boolean read, Pageable pageable);

    /**
     * Page des notifications d'un utilisateur : lues / non lues (read null = toutes) et types
     * (allTypes = true : tous ; types ne doit jamais etre vide, IN () est refuse par certaines bases).
     */
    @Query("SELECT n FROM Notification n WHERE n.recipient.id = :recipientId"
            + " AND (:read IS NULL OR n.read = :read)"
            + " AND (:allTypes = true OR n.type IN :types)")
    Page<Notification> search(@Param("recipientId") UUID recipientId, @Param("read") Boolean read,
                              @Param("allTypes") boolean allTypes,
                              @Param("types") java.util.Collection<com.loadpilot.backend.enums.NotificationType> types,
                              Pageable pageable);

    long countByRecipientIdAndReadFalse(UUID recipientId);

    /** Ownership verifie ici (recipientId) plutot que par un simple findById
     * suivi d'une comparaison en Java - jamais un utilisateur ne doit
     * pouvoir marquer/lire la notification d'un autre (voir
     * NotificationServiceImpl). */
    java.util.Optional<Notification> findByIdAndRecipientId(UUID id, UUID recipientId);

    @Modifying
    @Query("UPDATE Notification n SET n.read = true, n.readAt = :now WHERE n.recipient.id = :recipientId AND n.read = false")
    int markAllReadForRecipient(@Param("recipientId") UUID recipientId, @Param("now") java.time.Instant now);
}
