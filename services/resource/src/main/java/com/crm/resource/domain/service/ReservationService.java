package com.crm.resource.domain.service;

import com.crm.resource.common.error.ErrorCode;
import com.crm.resource.common.error.NotFoundException;
import com.crm.resource.domain.ReservationStatus;
import com.crm.resource.persistence.entity.Reservation;
import com.crm.resource.persistence.repository.ReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * The ONLY writer of {@code reservations.updated_at} (functional-design.md 9.5): every call
 * here sets status + failureReason + updatedAt = clock.instant() in one UPDATE. The column's
 * DEFAULT now() covers the insert only.
 */
@Service
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final Clock clock;

    public ReservationService(ReservationRepository reservationRepository, Clock clock) {
        this.reservationRepository = reservationRepository;
        this.clock = clock;
    }

    /** -> COMPLETED. Called from the saga's happy path (step 4). */
    @Transactional
    public void complete(UUID reservationId) {
        move(reservationId, ReservationStatus.COMPLETED, null);
    }

    /** -> FAILED, with failure_reason set. Called from compensation (step (4)). */
    @Transactional
    public void fail(UUID reservationId, String reason) {
        move(reservationId, ReservationStatus.FAILED, reason);
    }

    private void move(UUID reservationId, ReservationStatus status, String reason) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RESERVATION_NOT_FOUND,
                        "Reservation " + reservationId + " not found"));
        reservation.setStatus(status.name());
        reservation.setFailureReason(reason);
        reservation.setUpdatedAt(clock.instant());
        reservationRepository.save(reservation);
    }
}
