package cl.duoc.barriodigital.requests.domain;

import org.junit.jupiter.api.Test;

import static cl.duoc.barriodigital.requests.domain.EstadoTramite.*;
import static org.assertj.core.api.Assertions.assertThat;

class EstadoTramiteTest {

    @Test
    void flujo_normal_es_valido_paso_a_paso() {
        assertThat(INGRESADO.puedeTransicionarA(ADMITIDO)).isTrue();
        assertThat(ADMITIDO.puedeTransicionarA(EN_GESTION)).isTrue();
        assertThat(EN_GESTION.puedeTransicionarA(EN_TERRENO)).isTrue();
        assertThat(EN_TERRENO.puedeTransicionarA(RESUELTO)).isTrue();
    }

    @Test
    void no_puede_saltar_a_en_terreno_sin_admitir() {
        assertThat(INGRESADO.puedeTransicionarA(EN_TERRENO)).isFalse();
    }

    @Test
    void estados_terminales_no_transicionan_a_nada() {
        assertThat(RESUELTO.puedeTransicionarA(ADMITIDO)).isFalse();
        assertThat(RECHAZADO.puedeTransicionarA(EN_GESTION)).isFalse();
    }

    @Test
    void tramite_lanza_excepcion_en_transicion_invalida() {
        Tramite t = new Tramite(1L, "vecino1", "Bache en la calle", "Calle Falsa 123");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> t.cambiarEstado(EN_TERRENO, "func1"));
    }
}
