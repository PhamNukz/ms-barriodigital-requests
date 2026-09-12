package cl.duoc.barriodigital.requests.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "tramite")
public class Tramite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tipo_id", nullable = false)
    private Long tipoId;

    @Column(name = "vecino_username", nullable = false, length = 150)
    private String vecinoUsername;

    @Column(nullable = false, length = 500)
    private String descripcion;

    @Column(length = 300)
    private String direccion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoTramite estado = EstadoTramite.INGRESADO;

    @Column(name = "funcionario_asignado", length = 150)
    private String funcionarioAsignado;

    @Column(name = "fecha_ingreso", nullable = false, updatable = false)
    private Instant fechaIngreso = Instant.now();

    @Column(name = "fecha_admision")
    private Instant fechaAdmision;

    @Column(name = "fecha_resolucion")
    private Instant fechaResolucion;

    protected Tramite() {
    }

    public Tramite(Long tipoId, String vecinoUsername, String descripcion, String direccion) {
        this.tipoId = tipoId;
        this.vecinoUsername = vecinoUsername;
        this.descripcion = descripcion;
        this.direccion = direccion;
    }

    /** Aplica la transicion si es valida; lanza IllegalStateException si no. */
    public void cambiarEstado(EstadoTramite nuevo, String funcionario) {
        if (!estado.puedeTransicionarA(nuevo)) {
            throw new IllegalStateException("No se puede pasar de " + estado + " a " + nuevo);
        }
        this.estado = nuevo;
        this.funcionarioAsignado = funcionario;
        if (nuevo == EstadoTramite.ADMITIDO) this.fechaAdmision = Instant.now();
        if (nuevo == EstadoTramite.RESUELTO || nuevo == EstadoTramite.RECHAZADO) this.fechaResolucion = Instant.now();
    }

    public Long getId() { return id; }
    public Long getTipoId() { return tipoId; }
    public String getVecinoUsername() { return vecinoUsername; }
    public String getDescripcion() { return descripcion; }
    public String getDireccion() { return direccion; }
    public EstadoTramite getEstado() { return estado; }
    public String getFuncionarioAsignado() { return funcionarioAsignado; }
    public Instant getFechaIngreso() { return fechaIngreso; }
    public Instant getFechaAdmision() { return fechaAdmision; }
    public Instant getFechaResolucion() { return fechaResolucion; }
}
