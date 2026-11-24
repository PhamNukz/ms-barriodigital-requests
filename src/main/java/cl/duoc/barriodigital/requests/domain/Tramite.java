package cl.duoc.barriodigital.requests.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
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

    /** Funcionario que lo ingreso a nombre del vecino; null si lo ingreso el propio vecino. */
    @Column(name = "creado_por", length = 150)
    private String creadoPor;

    /** Punto marcado en el mapa (WGS84). Null en los tramites sin mapa. */
    @Column(precision = 9, scale = 6)
    private BigDecimal lat;

    @Column(precision = 9, scale = 6)
    private BigDecimal lng;

    /** Como llegar: cerro, pasaje, escalera, color de la casa. */
    @Column(length = 300)
    private String referencia;

    protected Tramite() {
    }

    public Tramite(Long tipoId, String vecinoUsername, String descripcion, String direccion) {
        this.tipoId = tipoId;
        this.vecinoUsername = vecinoUsername;
        this.descripcion = descripcion;
        this.direccion = direccion;
    }

    /** Tramite que un funcionario ingresa a nombre de un vecino: queda registrado quien lo hizo. */
    public Tramite(Long tipoId, String vecinoUsername, String descripcion, String direccion, String creadoPor) {
        this(tipoId, vecinoUsername, descripcion, direccion);
        this.creadoPor = creadoPor;
    }

    /** Punto del mapa y referencias; se redondea a la escala de la columna (6 decimales, ~0,1 m). */
    public void ubicar(BigDecimal lat, BigDecimal lng, String referencia) {
        this.lat = lat == null ? null : lat.setScale(6, RoundingMode.HALF_UP);
        this.lng = lng == null ? null : lng.setScale(6, RoundingMode.HALF_UP);
        this.referencia = referencia == null || referencia.isBlank() ? null : referencia.trim();
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
    public String getCreadoPor() { return creadoPor; }
    public BigDecimal getLat() { return lat; }
    public BigDecimal getLng() { return lng; }
    public String getReferencia() { return referencia; }
}
