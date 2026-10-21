package com.crm.provider.domain.service;

import com.crm.provider.api.dto.InstanceResponse;
import com.crm.provider.api.dto.ProvisionInstanceRequest;
import com.crm.provider.common.error.ErrorCode;
import com.crm.provider.common.error.ProviderException;
import com.crm.provider.persistence.InstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProviderServiceTest {
    private final ProvisionInstanceRequest request = new ProvisionInstanceRequest("token", "VM", 2, 4);
    private InstanceRepository repository;
    private ProviderSimulator simulator;
    private ProviderService service;

    @BeforeEach
    void setUp() {
        repository = mock(InstanceRepository.class);
        simulator = mock(ProviderSimulator.class);
        service = new ProviderService(repository, simulator);
    }

    @Test
    void provisionSimulatesBeforePersisting() {
        InstanceResponse instance = new InstanceResponse("i-mock-test", "ACTIVE");
        when(repository.createOrGet(request)).thenReturn(instance);
        assertThat(service.provision(request, "always-succeed")).isEqualTo(instance);
        verify(simulator).simulate("always-succeed");
        verify(repository).createOrGet(request);
    }

    @Test
    void replayReturnsExistingEvenWithForcedFailure() {
        InstanceResponse instance = new InstanceResponse("i-mock-test", "ACTIVE");
        when(repository.findByClientToken("token")).thenReturn(Optional.of(instance));
        assertThat(service.provision(request, "always-fail")).isEqualTo(instance);
        verifyNoInteractions(simulator);
        verify(repository, never()).createOrGet(request);
    }

    @Test
    void failureDoesNotPersistAnInstance() {
        doThrow(new ProviderException(ErrorCode.SIMULATED_FAILURE, "failure")).when(simulator).simulate("always-fail");
        ProviderException error = catchThrowableOfType(ProviderException.class,
                () -> service.provision(request, "always-fail"));
        assertThat(error.code()).isEqualTo(ErrorCode.SIMULATED_FAILURE);
        verify(repository, never()).createOrGet(request);
    }

    @Test
    void terminationUpdatesActiveInstance() {
        when(repository.findById("id")).thenReturn(Optional.of(new InstanceResponse("id", "ACTIVE")));
        assertThat(service.terminate("id", null)).isEqualTo(new InstanceResponse("id", "TERMINATED"));
        verify(simulator).simulate(null);
        verify(repository).terminate("id");
    }

    @Test
    void terminationReplayDoesNotSimulateOrUpdate() {
        when(repository.findById("id")).thenReturn(Optional.of(new InstanceResponse("id", "TERMINATED")));
        assertThat(service.terminate("id", "always-fail").status()).isEqualTo("TERMINATED");
        verifyNoInteractions(simulator);
        verify(repository, never()).terminate("id");
    }

    @Test
    void unknownInstanceReturnsNotFound() {
        ProviderException error = catchThrowableOfType(ProviderException.class, () -> service.terminate("missing", null));
        assertThat(error.code()).isEqualTo(ErrorCode.INSTANCE_NOT_FOUND);
        verifyNoInteractions(simulator);
    }
}
