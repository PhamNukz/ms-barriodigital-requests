package cl.duoc.barriodigital.requests.amqp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Nombres de la topologia AMQP, unica fuente: application.yml (barriodigital.rabbitmq.*).
 * Copia identica en notify y requests, igual que el paquete security.
 */
@ConfigurationProperties("barriodigital.rabbitmq")
public record RabbitProperties(Exchanges exchanges, Colas colas, RoutingKeys routingKeys) {

    public record Exchanges(String direct, String topic, String deadLetter) {}

    public record Colas(String email, String emailDlq,
                        String crew, String crewDlq,
                        String certificate, String certificateDlq) {}

    public record RoutingKeys(String emailSend, String emailTopic, String emailDlq,
                              String crewTicket, String crewTopic, String crewDlq,
                              String certificateGen, String certificateTopic, String certificateDlq) {}
}
