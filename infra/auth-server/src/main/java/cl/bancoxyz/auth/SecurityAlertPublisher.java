package cl.bancoxyz.auth;

import cl.bancoxyz.platform.events.payload.SecurityAlert;

/** Puerto de salida hacia el tópico security.alerts. */
public interface SecurityAlertPublisher {

    void publish(SecurityAlert alert);
}
