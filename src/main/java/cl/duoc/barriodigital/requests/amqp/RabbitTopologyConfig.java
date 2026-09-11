package cl.duoc.barriodigital.requests.amqp;

import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * El dueño de la topologia completa (colas, DLQ, bindings) es ms-barriodigital-notify.
 * Aqui solo se declaran los exchanges -- declarar un exchange que ya existe con
 * los mismos parametros es un no-op, asi que este productor puede arrancar
 * antes o despues que el consumidor sin fallar al publicar.
 */
@Configuration
public class RabbitTopologyConfig {

    @Bean
    MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    DirectExchange cmdDirectExchange() {
        return new DirectExchange("cmd.direct", true, false);
    }

    @Bean
    TopicExchange cmdTopicExchange() {
        return new TopicExchange("cmd.topic", true, false);
    }
}
