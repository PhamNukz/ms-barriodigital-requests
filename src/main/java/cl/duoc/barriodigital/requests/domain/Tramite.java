package cl.duoc.barriodigital.requests.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    /** Admin: oculto al vecino (lo sigue viendo el funcionario). */
    @Column(name = "visible_vecino", nullable = false)
    private boolean visibleVecino = true;

    @Column(name = "motivo_visibilidad", length = 200)
    private String motivoVisibilidad;

    /** Papelera (borrado logico): no null = eliminado. La fila no se borra y el cupo no se devuelve. */
    @Column(name = "eliminado_en")
    private Instant eliminadoEn;

    @Column(name = "eliminado_por", length = 150)
    private String eliminadoPor;

    public static final int MAX_CONTACTOS = 3;

    // EAGER: son a lo mas 3 y los leen la respuesta (fuera de la transaccion) y el relay asincrono.
    @OneToMany(mappedBy = "tramite", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("id")
    private List<Contacto> contactos = new ArrayList<>();

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

    public boolean eliminado() {
        return eliminadoEn != null;
    }

    /** Motivo obligatorio de las acciones del Admin: 3 a 200 caracteres sin contar los espacios de los bordes. */
    public static String motivo(String motivo) {
        String m = motivo == null ? "" : motivo.trim();
        if (m.length() < 3 || m.length() > 200) {
            throw new IllegalArgumentException("El motivo debe tener entre 3 y 200 caracteres");
        }
        return m;
    }

    /** @param motivo ya validado con {@link #motivo(String)}. */
    public void cambiarVisibilidad(boolean visible, String motivo) {
        if (eliminado()) throw new IllegalStateException("El tramite esta en la papelera");
        if (visibleVecino == visible) {
            throw new IllegalStateException(visible ? "El tramite ya es visible para el vecino" : "El tramite ya esta oculto al vecino");
        }
        this.visibleVecino = visible;
        this.motivoVisibilidad = motivo;
    }

    public void enviarAPapelera(String usuario, Instant ahora) {
        if (eliminado()) throw new IllegalStateException("El tramite ya esta en la papelera");
        this.eliminadoEn = ahora;
        this.eliminadoPor = usuario;
    }

    /** Sale de la papelera tal como estaba: visibleVecino no se toca. */
    public void restaurar() {
        if (!eliminado()) throw new IllegalStateException("El tramite no esta en la papelera");
        this.eliminadoEn = null;
        this.eliminadoPor = null;
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
    public Visita proponerVisita(LocalDate fecha, Visita.Franja franja, String cuadrilla, String nota,
                                 LocalDateTime ahoraLocal) {
        if (estado != EstadoTramite.ADMITIDO && estado != EstadoTramite.EN_GESTION && estado != EstadoTramite.EN_TERRENO) {
            throw new IllegalStateException("No se puede proponer una visita en estado " + estado);
        }
        if (fecha.isBefore(ahoraLocal.toLocalDate())) throw new IllegalArgumentException("La fecha de la visita ya paso");
        // Hoy solo si la franja aun no empieza: a las 15:00 ya no se puede proponer "hoy en la manana".
        if (!fecha.atTime(franja.horaInicio, 0).isAfter(ahoraLocal)) {
            throw new IllegalArgumentException("La franja " + franja + " de hoy ya empezo");
        }
        if (visita == null) visita = new Visita(this);
        visita.proponer(fecha, franja, cuadrilla, nota);
        enCaminoDesde = null;
        return visita;
    }

    /** @param hoy dia civil del municipio (Chile), no el de UTC. */
    public Visita responderVisita(Visita.Respuesta respuesta, String horarioPedido, String codigoVisto, LocalDate hoy) {
        visitaAbierta().responder(respuesta, horarioPedido, codigoVisto, hoy);
        return visita;
    }

    /** El funcionario confirmo por telefono. @return false si ya estaba confirmada. */
    public boolean confirmarVisitaPorTelefono(LocalDate hoy) {
        return visitaAbierta().confirmarPorTelefono(hoy);
    }

    private Visita visitaAbierta() {
        if (visita == null) throw new NoSuchElementException("El tramite " + id + " no tiene visita");
        if (cerrado()) throw new IllegalStateException("El tramite esta cerrado (" + estado + ")");
        return visita;
    }

    public Contacto agregarContacto(String nombre, String correo) {
        if (cerrado()) throw new IllegalStateException("El tramite esta cerrado (" + estado + ")");
        String normalizado = correo.trim().toLowerCase(Locale.ROOT);
        if (normalizado.equalsIgnoreCase(vecinoUsername)) {
            throw new IllegalStateException("Ese correo es el del vecino: ya recibe los avisos");
        }
        if (contactos.stream().anyMatch(c -> c.getCorreo().equals(normalizado))) {
            throw new IllegalStateException("Ese correo ya es contacto del tramite");
        }
        // ponytail: el maximo se cuenta en memoria; dos altas simultaneas con correos distintos
        // podrian dejar 4. El UNIQUE de V6 solo frena el mismo correo (doble clic).
        if (contactos.size() >= MAX_CONTACTOS) {
            throw new IllegalStateException("El tramite ya tiene " + MAX_CONTACTOS + " contactos");
        }
        Contacto nuevo = new Contacto(this, nombre.trim(), normalizado);
        contactos.add(nuevo);
        return nuevo;
    }

    public Contacto quitarContacto(Long contactoId) {
        Contacto c = contactos.stream().filter(x -> contactoId.equals(x.getId())).findFirst()
                .orElseThrow(() -> new NoSuchElementException("El contacto " + contactoId + " no es de este tramite"));
        contactos.remove(c);
        return c;
    }

    /** @return true si recien se marco; false si ya estaba en camino (idempotente, sin segundo aviso). */
    public boolean marcarEnCamino(Instant ahora) {
        if (estado != EstadoTramite.EN_TERRENO) {
            throw new IllegalStateException("Solo se avisa en camino en EN_TERRENO (esta en " + estado + ")");
        }
        if (enCaminoDesde != null) return false;
        // Tras reprogramar en EN_TERRENO la visita vuelve a PROPUESTA: no se sale a una fecha sin confirmar.
        if (visita != null && visita.getEstado() != Visita.Estado.CONFIRMADA) {
            throw new IllegalStateException("El vecino no ha confirmado la visita");
        }
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
    public List<Contacto> getContactos() { return List.copyOf(contactos); }
    public boolean isVisibleVecino() { return visibleVecino; }
    public String getMotivoVisibilidad() { return motivoVisibilidad; }
    public Instant getEliminadoEn() { return eliminadoEn; }
    public String getEliminadoPor() { return eliminadoPor; }
}
