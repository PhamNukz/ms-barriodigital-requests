package cl.duoc.barriodigital.requests.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Pool dedicado a publicar los eventos de tramite a RabbitMQ/Kafka fuera del
 * hilo del request HTTP (ver TramiteEventRelay).
 *
 * <p>La cola es acotada y el rechazo se descarta con log en vez de lanzar:
 * si se lanzara, la excepcion volveria al hilo del request (el proxy de @Async
 * hace submit ahi) y el usuario veria un error por una operacion que en la BD
 * ya quedo commiteada -- justo lo que este pool viene a evitar.
 */
@Configuration
@EnableAsync
public class EventRelayAsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(EventRelayAsyncConfig.class);

    @Bean("eventRelayExecutor")
    TaskExecutor eventRelayExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("event-relay-");
        executor.setRejectedExecutionHandler((task, pool) ->
                log.error("Cola de publicacion llena ({} tareas): se descarta un evento. "
                        + "Revisar si RabbitMQ/Kafka estan caidos.", pool.getQueue().size()));
        // Da tiempo a que terminen las publicaciones en vuelo cuando se apaga el contenedor.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }
}
