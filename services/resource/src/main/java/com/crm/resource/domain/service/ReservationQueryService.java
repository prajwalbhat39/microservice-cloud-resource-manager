package com.crm.resource.domain.service;

import com.crm.resource.api.dto.ReservationStatusResponse;
import com.crm.resource.common.error.ErrorCode;
import com.crm.resource.common.error.NotFoundException;
import com.crm.resource.persistence.entity.Reservation;
import com.crm.resource.persistence.entity.Resource;
import com.crm.resource.persistence.repository.ReservationRepository;
import com.crm.resource.persistence.repository.ResourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Read the provider instance ID from the linked resource row. */
@Service
public class ReservationQueryService {

    private final ReservationRepository reservationRepository;
    private final ResourceRepository resourceRepository;

    public ReservationQueryService(ReservationRepository reservationRepository,
                                   ResourceRepository resourceRepository) {
        this.reservationRepository = reservationRepository;
        this.resourceRepository = resourceRepository;
    }

    /**
     * Finds the reservation (404 RESERVATION_NOT_FOUND if absent), loads the linked Resource for
     * its provider_instance_id, and maps both into ReservationStatusResponse (functional-design.md
     * 4.2: happy path vs compensated, where instanceId is null).
     */
    @Transactional(readOnly = true)
    public ReservationStatusResponse get(UUID reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RESERVATION_NOT_FOUND,
                        "Reservation " + reservationId + " not found"));

        String instanceId = null;
        if (reservation.getResourceId() != null) {
            instanceId = resourceRepository.findById(reservation.getResourceId())
                    .map(Resource::getProviderInstanceId)
                    .orElse(null);
        }

        return new ReservationStatusResponse(
                reservation.getReservationId(),
                reservation.getStatus(),
                reservation.getResourceId(),
                instanceId,
                reservation.getFailureReason(),
                reservation.getCreatedAt(),
                reservation.getUpdatedAt());
    }
}
