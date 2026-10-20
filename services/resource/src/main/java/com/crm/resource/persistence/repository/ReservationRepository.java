package com.crm.resource.persistence.repository;

import com.crm.resource.persistence.entity.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    /** A tenant's reservations for demo queries and troubleshooting. */
    List<Reservation> findByTenantId(String tenantId);
}
