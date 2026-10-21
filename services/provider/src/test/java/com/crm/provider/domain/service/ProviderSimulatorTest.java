package com.crm.provider.domain.service;

import com.crm.provider.common.error.ErrorCode;
import com.crm.provider.common.error.ProviderException;
import com.crm.provider.config.ProviderProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderSimulatorTest {
    private RandomGenerator random;
    private Sleeper sleeper;
    private ProviderSimulator simulator;

    @BeforeEach
    void setUp() {
        random = mock(RandomGenerator.class);
        sleeper = mock(Sleeper.class);
        simulator = new ProviderSimulator(new ProviderProperties(0.2, 1000, 3000, 10000), random, sleeper);
    }

    @Test
    void randomDelayUsesInclusiveConfiguredBounds() throws InterruptedException {
        when(random.nextLong(1000, 3001)).thenReturn(3000L);
        when(random.nextDouble()).thenReturn(0.2);
        simulator.simulate(null);
        verify(sleeper).sleep(3000);
    }

    @Test
    void failsBelowButNotAtConfiguredRate() {
        when(random.nextDouble()).thenReturn(0.19999, 0.2);
        ProviderException error = catchThrowableOfType(ProviderException.class, () -> simulator.simulate(null));
        assertThat(error.code()).isEqualTo(ErrorCode.SIMULATED_FAILURE);
        simulator.simulate(null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"always-fail", "true", " ALWAYS-FAIL "})
    void forcedFailureOverridesRandomSuccess(String mode) {
        ProviderException error = catchThrowableOfType(ProviderException.class, () -> simulator.simulate(mode));
        assertThat(error.code()).isEqualTo(ErrorCode.SIMULATED_FAILURE);
        verify(random, never()).nextDouble();
    }

    @ParameterizedTest
    @ValueSource(strings = {"always-succeed", "false", " ALWAYS-SUCCEED "})
    void forcedSuccessOverridesRandomFailure(String mode) {
        simulator.simulate(mode);
        verify(random, never()).nextDouble();
    }

    @Test
    void slowModeUsesExplicitDelayAndSucceeds() throws InterruptedException {
        simulator.simulate("slow");
        verify(sleeper).sleep(10000);
        verify(random, never()).nextDouble();
    }

    @Test
    void unknownHeaderUsesConfiguredDefaults() {
        when(random.nextDouble()).thenReturn(0.1);
        assertThatThrownBy(() -> simulator.simulate("unsupported")).isInstanceOf(ProviderException.class);
        verify(random).nextDouble();
    }

    @Test
    void zeroRateNeverFailsAndFullRateAlwaysFails() {
        when(random.nextDouble()).thenReturn(0.0, Math.nextDown(1.0));
        new ProviderSimulator(new ProviderProperties(0, 0, 0, 0), random, sleeper).simulate(null);
        assertThatThrownBy(() ->
                new ProviderSimulator(new ProviderProperties(1, 0, 0, 0), random, sleeper).simulate(null))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void interruptionRestoresFlagAndSurfacesUnavailable() throws InterruptedException {
        when(random.nextLong(1000, 3001)).thenReturn(1000L);
        doThrow(new InterruptedException("shutdown")).when(sleeper).sleep(1000);
        try {
            ProviderException error = catchThrowableOfType(ProviderException.class,
                    () -> simulator.simulate("always-succeed"));
            assertThat(error.code()).isEqualTo(ErrorCode.PROVIDER_UNAVAILABLE);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
