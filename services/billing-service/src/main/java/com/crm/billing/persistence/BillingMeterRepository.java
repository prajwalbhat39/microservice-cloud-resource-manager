package com.crm.billing.persistence;

import com.crm.billing.domain.BillingMeter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillingMeterRepository extends JpaRepository<BillingMeter, UUID> {

    Optional<BillingMeter> findByResourceId(String resourceId);

    List<BillingMeter> findByTenantId(String tenantId);
}

