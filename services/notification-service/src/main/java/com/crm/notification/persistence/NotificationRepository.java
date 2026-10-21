package com.crm.notification.persistence;

import com.crm.notification.domain.DeliveryStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    List<Notification> findByTenantIdOrderByCreatedAtAscNotificationIdAsc(String tenantId);
    List<Notification> findTop100ByStatusAndAttemptsLessThanOrderByCreatedAtAsc(DeliveryStatus status, int attempts);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from Notification n where n.notificationId = :id")
    Optional<Notification> findForDelivery(UUID id);
}
