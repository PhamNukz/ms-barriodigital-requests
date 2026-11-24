package cl.duoc.barriodigital.requests.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.NoSuchElementException;

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

    /** Asignacion explicita del tablero (distinto de funcionarioAsignado: el ultimo que cambio el estado). */
    @Column(length = 150)
    private String responsable;

    /** La cuadrilla salio hacia la visita. Solo tiene sentido en EN_TERRENO. */
    @Column(name = "en_camino_desde")
    private Instant enCaminoDesde;

    @OneToOne(mappedBy = "tramite", cascade = CascadeType.ALL)
    private Visita visita;

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
        if (nuevo == EstadoTramite.EN_TERRENO && visita != null && visita.getEstado() != Visita.Estado.CONFIRMADA) {
            throw new IllegalStateException("El vecino no ha confirmado la visita");
        }
        this.estado = nuevo;
        this.funcionarioAsignado = funcionario;
        if (nuevo == EstadoTramite.EN_GESTION && responsable == null) this.responsable = funcionario;
        if (nuevo == EstadoTramite.ADMITIDO) this.fechaAdmision = Instant.now();
        if (nuevo == EstadoTramite.RESUELTO || nuevo == EstadoTramite.RECHAZADO) this.fechaResolucion = Instant.now();
    }

    public boolean cerrado() {
        return estado == EstadoTramite.RESUELTO || estado == EstadoTramite.RECHAZADO;
    }

    public void asignarResponsable(String correo) {
        if (cerrado()) throw new IllegalStateException("El tramite esta cerrado (" + estado + ")");
        this.responsable = correo;
    }

    /**
     * Crea o reemplaza la visita, que queda PROPUESTA. En EN_TERRENO es una reprogramacion:
     * el aviso "en camino" era de la visita anterior, asi que se borra.
     */
    public Visita proponerVisita(LocalDate fecha, Visita.Franja franja, String cuadrilla, String nota, LocalDate hoy) {
        if (estado != EstadoTramite.ADMITIDO && estado != EstadoTramite.EN_GESTION && estado != EstadoTramite.EN_TERRENO) {
            throw new IllegalStateException("No se puede proponer una visita en estado " + estado);
        }
        if (fecha.isBefore(hoy)) throw new IllegalArgumentException("La fecha de la visita ya paso");
        if (visita == null) visita = new Visita(this);
        visita.proponer(fecha, franja, cuadrilla, nota);
        enCaminoDesde = null;
        return visita;
    }

    public Visita responderVisita(Visita.Respuesta respuesta, String horarioPedido) {
        if (visita == null) throw new NoSuchElementException("El tramite " + id + " no tiene visita");
        if (cerrado()) throw new IllegalStateException("El tramite esta cerrado (" + estado + ")");
        visita.responder(respuesta, horarioPedido);
        return visita;
    }

    /** @return true si recien se marco; false si ya estaba en camino (idempotente, sin segundo aviso). */
    public boolean marcarEnCamino(Instant ahora) {
        if (estado != EstadoTramite.EN_TERRENO) {
            throw new IllegalStateException("Solo se avisa en camino en EN_TERRENO (esta en " + estado + ")");
        }
        if (enCaminoDesde != null) return false;
        enCaminoDesde = ahora;
        return true;
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
    public String getResponsable() { return responsable; }
    public Instant getEnCaminoDesde() { return enCaminoDesde; }
    public Visita getVisita() { return visita; }
}
