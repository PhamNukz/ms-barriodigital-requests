package cl.duoc.barriodigital.requests.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El cupo diario se cuenta sobre el dia civil local. Contarlo sobre el dia UTC
 * hacia que en Chile (UTC-3/-4) el cupo se reiniciara a las 21:00 y que las
 * admisiones de la noche anterior descontaran del cupo del dia siguiente.
 */
class CupoDiaLocalTest {

    private static final ZoneId SANTIAGO = ZoneId.of("America/Santiago");

    @Test
    void el_dia_empieza_a_medianoche_local_no_a_medianoche_utc() {
        // 2026-09-12 15:27Z = 12:27 del 12-sep en Santiago (UTC-3)
        Instant ahora = Instant.parse("2026-09-12T15:27:00Z");

        Instant inicio = TramiteService.inicioDelDiaLocal(SANTIAGO, ahora);

        // medianoche del 12-sep en Santiago = 03:00Z, no 00:00Z
        assertEquals(Instant.parse("2026-09-12T03:00:00Z"), inicio);
    }

    /**
     * Caso real que fallo en produccion: dos tramites admitidos a las 01:10Z del
     * 12-sep (22:10 del 11-sep en Chile) dejaban sin cupo el dia 12.
     */
    @Test
    void una_admision_de_anoche_no_consume_el_cupo_de_hoy() {
        Instant ahora = Instant.parse("2026-09-12T15:27:00Z");
        Instant admisionDeAnoche = Instant.parse("2026-09-12T01:10:00Z"); // 22:10 del 11-sep en Chile

        Instant inicio = TramiteService.inicioDelDiaLocal(SANTIAGO, ahora);

        assertTrue(admisionDeAnoche.isBefore(inicio),
                "la admision de anoche quedo dentro de la ventana de hoy");
    }

    @Test
    void una_admision_de_esta_manana_si_consume_el_cupo_de_hoy() {
        Instant ahora = Instant.parse("2026-09-12T15:27:00Z");
        Instant admisionDeHoy = Instant.parse("2026-09-12T13:00:00Z"); // 10:00 del 12-sep en Chile

        Instant inicio = TramiteService.inicioDelDiaLocal(SANTIAGO, ahora);

        assertTrue(!admisionDeHoy.isBefore(inicio) && !admisionDeHoy.isAfter(ahora));
    }
}
