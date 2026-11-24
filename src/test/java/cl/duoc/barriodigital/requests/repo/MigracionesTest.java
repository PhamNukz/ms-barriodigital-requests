package cl.duoc.barriodigital.requests.repo;

import cl.duoc.barriodigital.requests.domain.Tramite;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Corre las migraciones reales de Flyway (V1..V4) sobre H2 en modo Oracle, en vez del
 * create-drop del resto de los tests, y guarda/lee por JPA: si la entidad pide una
 * columna que ninguna migracion crea, el INSERT falla aqui y no al desplegar.
 * ponytail: H2 imita a Oracle, no es Oracle; el ddl-auto=validate de produccion es la
 * ultima palabra sobre tipos.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:migraciones;MODE=Oracle;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MigracionesTest {

    @Autowired
    TramiteRepository repo;
    @Autowired
    TestEntityManager em;

    @Test
    void v4_guarda_el_punto_del_mapa_y_la_referencia() {
        Tramite t = new Tramite(1L, "vecino1@duocuc.cl", "Bache", "Pilcomayo 581");
        t.ubicar(new BigDecimal("-33.0418123"), new BigDecimal("-71.6269"), "Escalera, casa azul");
        Long id = repo.saveAndFlush(t).getId();
        em.clear();

        Tramite leido = repo.findById(id).orElseThrow();
        assertEquals(new BigDecimal("-33.041812"), leido.getLat());
        assertEquals(new BigDecimal("-71.626900"), leido.getLng());
        assertEquals("Escalera, casa azul", leido.getReferencia());
    }

    @Test
    void un_tramite_sin_mapa_queda_con_ubicacion_null() {
        Long id = repo.saveAndFlush(new Tramite(1L, "vecino1@duocuc.cl", "Bache", "Calle Falsa 123")).getId();
        em.clear();

        Tramite leido = repo.findById(id).orElseThrow();
        assertNull(leido.getLat());
        assertNull(leido.getReferencia());
    }
}
