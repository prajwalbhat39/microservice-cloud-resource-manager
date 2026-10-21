package com.crm.billing.persistence;

import com.crm.billing.domain.BillingEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillingEventRepository extends JpaRepository<BillingEvent, Long> {

    List<BillingEvent> findByResourceId(String resourceId);
}

