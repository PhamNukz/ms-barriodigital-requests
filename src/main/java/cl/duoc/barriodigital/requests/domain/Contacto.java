package cl.duoc.barriodigital.requests.domain;

import jakarta.persistence.*;

/** Familiar que recibe copia de los avisos del tramite. Lo agrega o quita solo el vecino dueno. */
@Entity
@Table(name = "tramite_contacto")
public class Contacto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tramite_id", nullable = false)
    private Tramite tramite;

    @Column(nullable = false, length = 100)
    private String nombre;

    /** Siempre en minusculas (lo normaliza Tramite.agregarContacto). */
    @Column(nullable = false, length = 150)
    private String correo;

    protected Contacto() {
    }

    Contacto(Tramite tramite, String nombre, String correo) {
        this.tramite = tramite;
        this.nombre = nombre;
        this.correo = correo;
    }

    public Long getId() { return id; }
    public String getNombre() { return nombre; }
    public String getCorreo() { return correo; }
}
