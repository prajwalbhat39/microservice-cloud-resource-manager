package com.crm.provider.domain.service;

import com.crm.provider.api.dto.InstanceResponse;
import com.crm.provider.api.dto.ProvisionInstanceRequest;
import com.crm.provider.common.error.ErrorCode;
import com.crm.provider.common.error.ProviderException;
import com.crm.provider.persistence.InstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ProviderService {
    private static final Logger log = LoggerFactory.getLogger(ProviderService.class);
    private final InstanceRepository repository;
    private final ProviderSimulator simulator;

    public ProviderService(InstanceRepository repository, ProviderSimulator simulator) {
        this.repository = repository;
        this.simulator = simulator;
    }

    public InstanceResponse provision(ProvisionInstanceRequest request, String mode) {
        var existing = repository.findByClientToken(request.clientToken());
        if (existing.isPresent()) {
            log.info("provider.provision.replayed instanceId={}", existing.get().instanceId());
            return existing.get();
        }
        simulator.simulate(mode);
        InstanceResponse instance = repository.createOrGet(request);
        log.info("provider.provisioned instanceId={}", instance.instanceId());
        return instance;
    }

    public InstanceResponse terminate(String id, String mode) {
        InstanceResponse instance = repository.findById(id)
                .orElseThrow(() -> new ProviderException(ErrorCode.INSTANCE_NOT_FOUND, "Instance not found"));
        if (!instance.status().equals("TERMINATED")) {
            simulator.simulate(mode);
            repository.terminate(id);
        }
        log.info("provider.terminated instanceId={}", id);
        return new InstanceResponse(id, "TERMINATED");
    }
}
