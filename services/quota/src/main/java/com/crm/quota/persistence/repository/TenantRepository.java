package com.crm.quota.persistence.repository;

import com.crm.quota.persistence.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, String> {
}
