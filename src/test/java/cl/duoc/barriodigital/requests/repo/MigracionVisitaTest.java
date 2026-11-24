package cl.duoc.barriodigital.requests.repo;

import cl.duoc.barriodigital.requests.domain.EstadoTramite;
import cl.duoc.barriodigital.requests.domain.ReglaVisibilidad;
import cl.duoc.barriodigital.requests.domain.Tramite;
import cl.duoc.barriodigital.requests.domain.Visita;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * V5 (responsable, en_camino_desde, tabla visita) con las migraciones reales sobre H2 en modo
 * Oracle, no con create-drop: si la entidad pide una columna que V5 no crea, falla aqui.
 * ponytail: mismo enfoque que MigracionesTest de feat/mapa-direccion; al mergear ambas ramas
 * se pueden juntar en una clase.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:migracion-visita;MODE=Oracle;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MigracionVisitaTest {

    @Autowired
    TramiteRepository repo;
    @Autowired
    TestEntityManager em;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ReglaVisibilidadRepository reglas;

    @Test
    void v5_guarda_responsable_en_camino_y_visita() {
        Tramite t = new Tramite(1L, "vecino1@duocuc.cl", "Poda", "Calle 1");
        t.cambiarEstado(EstadoTramite.ADMITIDO, "func1");
        t.cambiarEstado(EstadoTramite.EN_GESTION, "func1");
        t.proponerVisita(LocalDate.of(2030, 1, 15), Visita.Franja.TARDE, "Cuadrilla Norte", "Ramas en la vereda",
                LocalDateTime.of(2030, 1, 1, 8, 0));
        String codigo = t.getVisita().getCodigo();
        t.responderVisita(Visita.Respuesta.CAMBIAR, "vie 18, manana", null, LocalDate.of(2030, 1, 1));
        t.agregarContacto("Hija", "hija@correo.cl");
        Long id = repo.saveAndFlush(t).getId();
        em.clear();

        Tramite leido = repo.findAll(org.springframework.data.domain.Sort.by("id")).stream()
                .filter(x -> x.getId().equals(id)).findFirst().orElseThrow();
        assertEquals("func1", leido.getResponsable());
        Visita v = leido.getVisita();
        assertNotNull(v);
        assertEquals(LocalDate.of(2030, 1, 15), v.getFecha());
        assertEquals(Visita.Franja.TARDE, v.getFranja());
        assertEquals(Visita.Estado.CAMBIO_PEDIDO, v.getEstado());
        assertEquals("vie 18, manana", v.getHorarioPedido());
        assertNotNull(v.getActualizadoEn());
        assertEquals(codigo, v.getCodigo());
        assertEquals("hija@correo.cl", leido.getContactos().get(0).getCorreo());
    }

    /** V7: la fila de reglas por defecto y las columnas de visibilidad y papelera. */
    @Test
    void v7_trae_las_reglas_por_defecto_y_guarda_visibilidad_y_papelera() {
        ReglaVisibilidad r = reglas.findById(ReglaVisibilidad.ID).orElseThrow();
        assertTrue(r.isMostrarNombreFuncionario());
        assertTrue(r.isMostrarRechazados());
        assertEquals(12, r.getMesesHistorial());
        assertEquals(90, r.getDiasPapelera());
        assertEquals(ReglaVisibilidad.AlVencer.ANONIMIZAR, r.getAlVencer());
        assertFalse(r.isAuditorRestaura());

        Tramite t = new Tramite(1L, "vecino1@duocuc.cl", "Poda", "Calle 1");
        t.cambiarVisibilidad(false, "Duplicado del tramite anterior, ingresó dos veces");
        t.enviarAPapelera("admin@duocuc.cl", java.time.Instant.parse("2030-01-01T12:00:00Z"));
        Long id = repo.saveAndFlush(t).getId();
        em.clear();

        Tramite leido = repo.findById(id).orElseThrow();
        assertFalse(leido.isVisibleVecino());
        assertTrue(leido.getMotivoVisibilidad().endsWith("ingresó dos veces"));
        assertEquals("admin@duocuc.cl", leido.getEliminadoPor());
        assertTrue(leido.eliminado());
    }

    /** V6: el UNIQUE (tramite_id, correo) frena el doble clic aunque la app no alcance a verlo. */
    @Test
    void v6_no_deja_el_mismo_contacto_dos_veces() {
        Long id = repo.saveAndFlush(new Tramite(1L, "vecino1@duocuc.cl", "Poda", "Calle 1")).getId();
        String insert = "INSERT INTO tramite_contacto (tramite_id, nombre, correo) VALUES (?, 'Hija', 'hija@correo.cl')";
        jdbc.update(insert, id);

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, id));
    }

    /**
     * V6: version de la visita. El vecino responde (b) mientras un funcionario reprograma con la
     * visita que cargo antes (a): sin version, el segundo en guardar pisaba al primero en silencio.
     */
    @Test
    void v6_la_reprogramacion_con_datos_viejos_choca_con_la_respuesta_del_vecino() {
        Tramite t = new Tramite(1L, "vecino1@duocuc.cl", "Poda", "Calle 1");
        t.cambiarEstado(EstadoTramite.ADMITIDO, "func1");
        t.proponerVisita(LocalDate.of(2030, 1, 15), Visita.Franja.TARDE, "Norte", null, LocalDateTime.of(2030, 1, 1, 8, 0));
        Long id = repo.saveAndFlush(t).getId();
        em.clear();

        Tramite a = repo.findById(id).orElseThrow();
        em.detach(a);
        Tramite b = repo.findById(id).orElseThrow();
        b.responderVisita(Visita.Respuesta.CONFIRMAR, null, null, LocalDate.of(2030, 1, 1));
        repo.saveAndFlush(b);

        a.proponerVisita(LocalDate.of(2030, 1, 20), Visita.Franja.MANANA, "Sur", null, LocalDateTime.of(2030, 1, 1, 8, 0));
        assertThrows(ObjectOptimisticLockingFailureException.class, () -> repo.saveAndFlush(a));
    }
}
