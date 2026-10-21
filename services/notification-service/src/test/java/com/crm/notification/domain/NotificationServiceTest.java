package com.crm.notification.domain;

import com.crm.notification.config.DeliverySettings;
import com.crm.notification.persistence.*;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {
    @Mock NotificationRepository repository;
    @Mock DeliverySimulator delivery;
    NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(repository, delivery, new DeliverySettings(3, 0, 30000), Clock.systemUTC());
    }

    @Test
    void successfulDeliveryIsRecordedWithoutRetrying() {
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        Notification result = service.record("tenant", "resource", EventType.ACTIVE, "Ready");
        when(repository.findForDelivery(result.getNotificationId())).thenReturn(Optional.of(result));
        service.retry(result.getNotificationId());
        assertThat(result.getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(result.getSentAt()).isNotNull();
        verify(delivery, times(1)).send(result);
    }

    @Test
    void failedDeliveryStaysPendingAndCanRecover() {
        doThrow(new IllegalStateException("delivery unavailable")).doNothing().when(delivery).send(any());
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        Notification result = service.record("tenant", "resource", EventType.FAILED, "Failed");
        assertThat(result.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(result.getSentAt()).isNull();
        when(repository.findForDelivery(result.getNotificationId())).thenReturn(Optional.of(result));
        service.retry(result.getNotificationId());
        assertThat(result.getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(result.getAttempts()).isEqualTo(2);
    }

    @Test
    void exhaustedDeliveryRemainsVisibleAndCannotRetryAgain() {
        doThrow(new IllegalStateException("delivery unavailable")).when(delivery).send(any());
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        Notification result = service.record("tenant", "resource", EventType.TERMINATED, "Terminated");
        when(repository.findForDelivery(result.getNotificationId())).thenReturn(Optional.of(result));
        service.retry(result.getNotificationId());
        service.retry(result.getNotificationId());
        service.retry(result.getNotificationId());
        assertThat(result.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(result.getAttempts()).isEqualTo(3);
        assertThat(result.getSentAt()).isNull();
        verify(delivery, times(3)).send(result);
    }

    @Test
    void simulationCanForceFailureWithoutARealProvider() {
        var simulator = new DeliverySimulator(new DeliverySettings(3, 1, 30000));
        assertThatThrownBy(() -> simulator.send(new Notification("tenant", "resource", EventType.ACTIVE,
                "Ready", Clock.systemUTC().instant()))).isInstanceOf(IllegalStateException.class);
    }
}
