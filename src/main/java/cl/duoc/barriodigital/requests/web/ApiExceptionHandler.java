package cl.duoc.barriodigital.requests.web;

import cl.duoc.barriodigital.requests.service.CupoExcedidoException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail noEncontrado(NoSuchElementException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail transicionInvalida(IllegalStateException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    /** Pedido valido en forma pero imposible (p. ej. fecha estimada sobre un paso ya cumplido). */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail pedidoInvalido(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * Dos usuarios cambiaron lo mismo a la vez: el vecino respondio la visita mientras la
     * reprogramaban (version de Visita), o un doble clic choco con un UNIQUE (contacto, visita).
     */
    @ExceptionHandler({OptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    ProblemDetail cambioSimultaneo(RuntimeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Otro usuario modifico el tramite al mismo tiempo; recarga e intenta de nuevo");
    }

    @ExceptionHandler(CupoExcedidoException.class)
    ProblemDetail cupoExcedido(CupoExcedidoException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }
}
