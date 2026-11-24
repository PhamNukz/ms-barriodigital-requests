package cl.duoc.barriodigital.requests.repo;

import cl.duoc.barriodigital.requests.domain.EstadoTramite;
import cl.duoc.barriodigital.requests.domain.Tramite;
import cl.duoc.barriodigital.requests.domain.Visita;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

    @Test
    void v5_guarda_responsable_en_camino_y_visita() {
        Tramite t = new Tramite(1L, "vecino1@duocuc.cl", "Poda", "Calle 1");
        t.cambiarEstado(EstadoTramite.ADMITIDO, "func1");
        t.cambiarEstado(EstadoTramite.EN_GESTION, "func1");
        t.proponerVisita(LocalDate.of(2030, 1, 15), Visita.Franja.TARDE, "Cuadrilla Norte", "Ramas en la vereda",
                LocalDate.of(2030, 1, 1));
        t.responderVisita(Visita.Respuesta.CAMBIAR, "vie 18, manana");
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
    }
}
