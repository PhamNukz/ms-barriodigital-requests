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
    void el_cupo_se_reinicia_a_medianoche_local_del_dia_siguiente() {
        Instant ahora = Instant.parse("2026-09-12T15:27:00Z"); // 12:27 del 12-sep en Santiago

        Instant reinicia = TramiteService.inicioDelDiaSiguienteLocal(SANTIAGO, ahora);

        // medianoche del 13-sep en Santiago (UTC-3) = 03:00Z
        assertEquals(Instant.parse("2026-09-13T03:00:00Z"), reinicia);
    }

    /**
     * En el cambio a horario de verano el dia local dura 23 horas: sumar 24h al
     * inicio del dia caeria una hora despues de la medianoche siguiente.
     *
     * <p>Fecha verificada contra la tzdata del JDK: la transicion de 2026 en
     * America/Santiago es el 6-sep 00:00 -> 01:00 (UTC-4 -> UTC-3).
     */
    @Test
    void el_reinicio_respeta_el_cambio_de_horario() {
        Instant ahora = Instant.parse("2026-09-06T16:00:00Z"); // 13:00 del 6-sep, ya en UTC-3

        Instant inicioHoy = TramiteService.inicioDelDiaLocal(SANTIAGO, ahora);
        Instant reinicia = TramiteService.inicioDelDiaSiguienteLocal(SANTIAGO, ahora);

        long horas = java.time.Duration.between(inicioHoy, reinicia).toHours();
        assertEquals(23, horas, "ese dia local dura 23 horas, no 24");
    }

    @Test
    void una_admision_de_esta_manana_si_consume_el_cupo_de_hoy() {
        Instant ahora = Instant.parse("2026-09-12T15:27:00Z");
        Instant admisionDeHoy = Instant.parse("2026-09-12T13:00:00Z"); // 10:00 del 12-sep en Chile

        Instant inicio = TramiteService.inicioDelDiaLocal(SANTIAGO, ahora);

        assertTrue(!admisionDeHoy.isBefore(inicio) && !admisionDeHoy.isAfter(ahora));
    }
}
