package cl.duoc.barriodigital.requests.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

/** Casos de borde de visita y contactos que dependen de la hora: sin Spring, con "ahora" fijo. */
class VisitaReglasTest {

    private static final ZoneId CHILE = ZoneId.of("America/Santiago");
    private static final LocalDate HOY = LocalDate.of(2030, 1, 15);

    private static Tramite admitido() {
        Tramite t = new Tramite(1L, "Vecino1@duocuc.cl", "Poda", "Calle 1");
        t.cambiarEstado(EstadoTramite.ADMITIDO, "func1");
        return t;
    }

    private static Visita proponer(Tramite t, LocalDate fecha, Visita.Franja franja, LocalDateTime ahora) {
        return t.proponerVisita(fecha, franja, "Cuadrilla Norte", null, ahora);
    }

    @Test
    void el_codigo_conserva_los_ceros_a_la_izquierda() {
        assertEquals("0000", Visita.codigo(0));
        assertEquals("0007", Visita.codigo(7));
        assertEquals("0420", Visita.codigo(420));
        assertEquals("9999", Visita.codigo(9999));
    }

    @Test
    void reprogramar_siempre_cambia_el_codigo() {
        Tramite t = admitido();
        String anterior = proponer(t, HOY.plusDays(1), Visita.Franja.MANANA, HOY.atTime(8, 0)).getCodigo();
        for (int i = 0; i < 200; i++) {
            String nuevo = proponer(t, HOY.plusDays(1), Visita.Franja.MANANA, HOY.atTime(8, 0)).getCodigo();
            assertTrue(nuevo.matches("\\d{4}"), nuevo);
            assertNotEquals(anterior, nuevo);
            anterior = nuevo;
        }
    }

    @Test
    void hoy_solo_si_la_franja_aun_no_empieza() {
        Tramite t = admitido();
        // 08:59: la manana de hoy todavia sirve
        assertDoesNotThrow(() -> proponer(t, HOY, Visita.Franja.MANANA, HOY.atTime(8, 59)));
        // 09:00 en punto: la franja ya empezo
        assertThrows(IllegalArgumentException.class, () -> proponer(t, HOY, Visita.Franja.MANANA, HOY.atTime(9, 0)));
        // 15:00: ni la manana ni la tarde de hoy
        assertThrows(IllegalArgumentException.class, () -> proponer(t, HOY, Visita.Franja.MANANA, HOY.atTime(15, 0)));
        assertThrows(IllegalArgumentException.class, () -> proponer(t, HOY, Visita.Franja.TARDE, HOY.atTime(15, 0)));
        // 13:59: la tarde de hoy si
        assertDoesNotThrow(() -> proponer(t, HOY, Visita.Franja.TARDE, HOY.atTime(13, 59)));
        // 23:59 de ayer: manana temprano si
        assertDoesNotThrow(() -> proponer(t, HOY, Visita.Franja.MANANA, HOY.minusDays(1).atTime(23, 59)));
    }

    /** La fecha es dia civil de Chile: el inicio en UTC cambia con el horario de verano. */
    @Test
    void el_inicio_de_la_franja_es_hora_de_chile_con_verano_e_invierno() {
        Tramite t = admitido();
        Visita enero = proponer(t, LocalDate.of(2030, 1, 15), Visita.Franja.MANANA, HOY.atTime(8, 0));
        assertEquals(Instant.parse("2030-01-15T12:00:00Z"), enero.inicio(CHILE)); // UTC-3

        Visita julio = proponer(t, LocalDate.of(2030, 7, 15), Visita.Franja.TARDE, HOY.atTime(8, 0));
        assertEquals(Instant.parse("2030-07-15T18:00:00Z"), julio.inicio(CHILE)); // UTC-4
    }

    @Test
    void el_vecino_responde_con_un_codigo_viejo_y_no_confirma_otra_fecha() {
        Tramite t = admitido();
        String visto = proponer(t, HOY.plusDays(1), Visita.Franja.MANANA, HOY.atTime(8, 0)).getCodigo();
        proponer(t, HOY.plusDays(3), Visita.Franja.TARDE, HOY.atTime(8, 0)); // reprogramada mientras miraba

        assertThrows(IllegalStateException.class, () -> t.responderVisita(Visita.Respuesta.CONFIRMAR, null, visto, HOY));
        assertEquals(Visita.Estado.PROPUESTA, t.getVisita().getEstado());
        t.responderVisita(Visita.Respuesta.CONFIRMAR, null, t.getVisita().getCodigo(), HOY);
        assertEquals(Visita.Estado.CONFIRMADA, t.getVisita().getEstado());
    }

    @Test
    void confirmar_por_telefono_borra_el_horario_pedido_y_es_idempotente() {
        Tramite t = admitido();
        proponer(t, HOY.plusDays(1), Visita.Franja.MANANA, HOY.atTime(8, 0));
        t.responderVisita(Visita.Respuesta.CAMBIAR, "vie, tarde", null, HOY);

        assertTrue(t.confirmarVisitaPorTelefono(HOY));
        assertEquals(Visita.Estado.CONFIRMADA, t.getVisita().getEstado());
        assertNull(t.getVisita().getHorarioPedido());
        assertFalse(t.confirmarVisitaPorTelefono(HOY));
    }

    @Test
    void una_visita_de_ayer_no_se_responde_ni_se_confirma_por_telefono() {
        Tramite t = admitido();
        proponer(t, HOY, Visita.Franja.TARDE, HOY.atTime(8, 0));
        LocalDate manana = HOY.plusDays(1);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> t.responderVisita(Visita.Respuesta.CONFIRMAR, null, null, manana));
        assertEquals("La fecha de la visita ya pasó: reprograma", e.getMessage());
        assertThrows(IllegalStateException.class, () -> t.confirmarVisitaPorTelefono(manana));
        assertEquals(Visita.Estado.PROPUESTA, t.getVisita().getEstado());

        // el mismo dia de la visita si vale, aunque la franja ya haya empezado
        assertTrue(t.confirmarVisitaPorTelefono(HOY));
    }

    @Test
    void con_el_tramite_cerrado_la_visita_no_se_responde_ni_se_confirma() {
        Tramite t = admitido();
        proponer(t, HOY.plusDays(1), Visita.Franja.MANANA, HOY.atTime(8, 0));
        t.cambiarEstado(EstadoTramite.RECHAZADO, "func1");

        assertThrows(IllegalStateException.class, () -> t.responderVisita(Visita.Respuesta.CONFIRMAR, null, null, HOY));
        assertThrows(IllegalStateException.class, () -> t.confirmarVisitaPorTelefono(HOY));
        assertThrows(IllegalStateException.class,
                () -> proponer(t, HOY.plusDays(2), Visita.Franja.MANANA, HOY.atTime(8, 0)));
    }

    @Test
    void no_se_sale_en_camino_a_una_visita_reprogramada_sin_confirmar() {
        Tramite t = admitido();
        t.cambiarEstado(EstadoTramite.EN_GESTION, "func1");
        proponer(t, HOY.plusDays(1), Visita.Franja.MANANA, HOY.atTime(8, 0));
        t.responderVisita(Visita.Respuesta.CONFIRMAR, null, null, HOY);
        t.cambiarEstado(EstadoTramite.EN_TERRENO, "func1");
        proponer(t, HOY.plusDays(2), Visita.Franja.MANANA, HOY.atTime(8, 0)); // vuelve a PROPUESTA

        assertThrows(IllegalStateException.class, () -> t.marcarEnCamino(Instant.now()));
        t.confirmarVisitaPorTelefono(HOY);
        assertTrue(t.marcarEnCamino(Instant.now()));
    }

    @Test
    void contactos_sin_el_vecino_sin_repetir_y_maximo_tres() {
        Tramite t = admitido();
        // el correo del vecino, con otra capitalizacion
        assertThrows(IllegalStateException.class, () -> t.agregarContacto("Yo", "vecino1@DUOCUC.cl"));

        t.agregarContacto("Hija", "  Hija@Correo.cl ");
        assertEquals("hija@correo.cl", t.getContactos().get(0).getCorreo());
        assertThrows(IllegalStateException.class, () -> t.agregarContacto("Hija otra vez", "HIJA@correo.cl"));

        t.agregarContacto("Hijo", "hijo@correo.cl");
        t.agregarContacto("Nieta", "nieta@correo.cl");
        assertThrows(IllegalStateException.class, () -> t.agregarContacto("Cuarto", "cuarto@correo.cl"));
        assertEquals(Tramite.MAX_CONTACTOS, t.getContactos().size());

        t.cambiarEstado(EstadoTramite.RECHAZADO, "func1");
        assertThrows(IllegalStateException.class, () -> t.agregarContacto("Tarde", "tarde@correo.cl"));
    }
}
