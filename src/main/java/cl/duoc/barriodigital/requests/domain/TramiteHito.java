package cl.duoc.barriodigital.requests.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Un paso del flujo de un tramite. Se crea al cumplirse el estado (fechaCumplido)
 * o antes, cuando el funcionario programa una fecha estimada y deja una nota.
 */
@Entity
@Table(name = "tramite_hito", uniqueConstraints = @UniqueConstraint(columnNames = {"tramite_id", "estado"}))
public class TramiteHito {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tramite_id", nullable = false)
    private Long tramiteId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoTramite estado;

    @Column(name = "fecha_cumplido")
    private Instant fechaCumplido;

    @Column(name = "fecha_estimada")
    private Instant fechaEstimada;

    @Column(length = 500)
    private String nota;

    @Column(name = "actualizado_por", length = 150)
    private String actualizadoPor;

    protected TramiteHito() {
    }

    public TramiteHito(Long tramiteId, EstadoTramite estado) {
        this.tramiteId = tramiteId;
        this.estado = estado;
    }

    /** Marca el paso como cumplido. Una nota vacia no borra la que ya habia programada. */
    public void cumplir(Instant cuando, String usuario, String notaNueva) {
        this.fechaCumplido = cuando;
        this.actualizadoPor = usuario;
        if (notaNueva != null && !notaNueva.isBlank()) this.nota = notaNueva.trim();
    }

    /** Fecha estimada y nota que programa el funcionario; null o vacio las borra. */
    public void planificar(Instant fechaEstimada, String notaNueva, String usuario) {
        this.fechaEstimada = fechaEstimada;
        this.nota = notaNueva == null || notaNueva.isBlank() ? null : notaNueva.trim();
        this.actualizadoPor = usuario;
    }

    public Long getId() { return id; }
    public Long getTramiteId() { return tramiteId; }
    public EstadoTramite getEstado() { return estado; }
    public Instant getFechaCumplido() { return fechaCumplido; }
    public Instant getFechaEstimada() { return fechaEstimada; }
    public String getNota() { return nota; }
    public String getActualizadoPor() { return actualizadoPor; }
}
