package cl.duoc.barriodigital.requests.domain;

import jakarta.persistence.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * Visita en terreno: el funcionario la propone y el vecino la responde con un boton.
 * Una por tramite; reprogramar reemplaza fecha, franja y cuadrilla y la deja PROPUESTA de nuevo.
 */
@Entity
@Table(name = "visita")
public class Visita {

    public enum Franja {
        MANANA(9), TARDE(14);

        /** Hora local (Chile) en que empieza la franja: MANANA 9 a 12, TARDE 14 a 17. */
        public final int horaInicio;

        Franja(int horaInicio) {
            this.horaInicio = horaInicio;
        }
    }

    public enum Estado { PROPUESTA, CONFIRMADA, CAMBIO_PEDIDO, LLAMAR }

    public enum Respuesta {
        CONFIRMAR(Estado.CONFIRMADA), CAMBIAR(Estado.CAMBIO_PEDIDO), LLAMAR(Estado.LLAMAR);

        final Estado estado;

        Respuesta(Estado estado) {
            this.estado = estado;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tramite_id", nullable = false, unique = true)
    private Tramite tramite;

    @Column(nullable = false)
    private LocalDate fecha;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Franja franja;

    @Column(nullable = false, length = 100)
    private String cuadrilla;

    @Column(length = 500)
    private String nota;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Estado estado;

    @Column(name = "horario_pedido", length = 100)
    private String horarioPedido;

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn;

    /** 4 digitos, nuevo en cada propuesta. Texto: "0042" no es 42. Null en visitas anteriores a V6. */
    @Column(length = 4)
    private String codigo;

    /** Bloqueo optimista: si el vecino responde mientras alguien reprograma, el segundo recibe 409. */
    @Version
    private Integer version;

    private static final SecureRandom AZAR = new SecureRandom();

    /** Locale.ROOT: con otro locale por defecto, %d podria escribir digitos no ASCII. */
    static String codigo(int n) {
        return String.format(Locale.ROOT, "%04d", n);
    }

    protected Visita() {
    }

    Visita(Tramite tramite) {
        this.tramite = tramite;
    }

    void proponer(LocalDate fecha, Franja franja, String cuadrilla, String nota) {
        this.fecha = fecha;
        this.franja = franja;
        this.cuadrilla = cuadrilla.trim();
        this.nota = nota == null || nota.isBlank() ? null : nota.trim();
        this.estado = Estado.PROPUESTA;
        this.horarioPedido = null;
        this.actualizadoEn = Instant.now().truncatedTo(ChronoUnit.MILLIS); // la BD guarda micros
        String anterior = codigo;
        do {
            codigo = codigo(AZAR.nextInt(10_000));
        } while (codigo.equals(anterior)); // reprogramar siempre cambia el codigo
    }

    /** Momento en que empieza la franja, en la zona del municipio. */
    public Instant inicio(ZoneId zona) {
        return fecha.atTime(franja.horaInicio, 0).atZone(zona).toInstant();
    }

    /**
     * @param codigoVisto el codigo de la visita que el vecino tenia en pantalla, o null. Si no
     *                    coincide, alguien la reprogramo mientras tanto: confirmaria otra fecha.
     */
    void responder(Respuesta respuesta, String horarioPedido, String codigoVisto) {
        if (codigoVisto != null && !codigoVisto.equals(codigo)) {
            throw new IllegalStateException("La visita cambio: revisa la nueva fecha antes de responder");
        }
        if (estado != Estado.PROPUESTA) {
            throw new IllegalStateException("La visita ya fue respondida (" + estado + ")");
        }
        boolean cambia = respuesta == Respuesta.CAMBIAR;
        if (cambia && (horarioPedido == null || horarioPedido.isBlank())) {
            throw new IllegalArgumentException("Para pedir otro horario hay que indicar cual (horarioPedido)");
        }
        this.estado = respuesta.estado;
        this.horarioPedido = cambia ? horarioPedido.trim() : null;
        this.actualizadoEn = Instant.now().truncatedTo(ChronoUnit.MILLIS); // la BD guarda micros
    }

    /** @return false si ya estaba CONFIRMADA (idempotente: sin auditoria ni correo de nuevo). */
    boolean confirmarPorTelefono() {
        if (estado == Estado.CONFIRMADA) return false;
        this.estado = Estado.CONFIRMADA;
        this.horarioPedido = null;
        this.actualizadoEn = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return true;
    }

    public LocalDate getFecha() { return fecha; }
    public Franja getFranja() { return franja; }
    public String getCuadrilla() { return cuadrilla; }
    public String getNota() { return nota; }
    public Estado getEstado() { return estado; }
    public String getHorarioPedido() { return horarioPedido; }
    public Instant getActualizadoEn() { return actualizadoEn; }
    public String getCodigo() { return codigo; }
}
