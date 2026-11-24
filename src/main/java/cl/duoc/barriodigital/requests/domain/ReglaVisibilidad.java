package cl.duoc.barriodigital.requests.domain;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Reglas de visibilidad que fija el Admin. Una sola fila (id = 1); sin fila valen los valores por defecto. */
@Entity
@Table(name = "regla_visibilidad")
public class ReglaVisibilidad {

    public static final long ID = 1L;
    static final Set<Integer> MESES_HISTORIAL = Set.of(6, 12, 24);
    static final Set<Integer> DIAS_PAPELERA = Set.of(30, 90, 180);

    public enum AlVencer { ANONIMIZAR, BORRAR }

    @Id
    private Long id = ID;

    @Column(name = "mostrar_nombre_funcionario", nullable = false)
    private boolean mostrarNombreFuncionario = true;

    @Column(name = "mostrar_rechazados", nullable = false)
    private boolean mostrarRechazados = true;

    @Column(name = "meses_historial", nullable = false)
    private int mesesHistorial = 12;

    @Column(name = "dias_papelera", nullable = false)
    private int diasPapelera = 90;

    // ponytail: se guarda, pero el proceso que anonimiza o borra lo vencido en la papelera no
    // existe todavia (el contrato lo deja pendiente). Se agrega como @Scheduled cuando se pida.
    @Enumerated(EnumType.STRING)
    @Column(name = "al_vencer", nullable = false, length = 12)
    private AlVencer alVencer = AlVencer.ANONIMIZAR;

    @Column(name = "auditor_restaura", nullable = false)
    private boolean auditorRestaura = false;

    /**
     * Reemplaza todas las reglas. Un valor fuera de las opciones lanza IllegalArgumentException (400).
     * @return lo que cambio, para la auditoria ("mesesHistorial 12 -> 24, ..."); vacio si nada.
     */
    public String cambiar(boolean mostrarNombreFuncionario, boolean mostrarRechazados, int mesesHistorial,
                          int diasPapelera, AlVencer alVencer, boolean auditorRestaura) {
        if (!MESES_HISTORIAL.contains(mesesHistorial)) {
            throw new IllegalArgumentException("mesesHistorial debe ser 6, 12 o 24");
        }
        if (!DIAS_PAPELERA.contains(diasPapelera)) {
            throw new IllegalArgumentException("diasPapelera debe ser 30, 90 o 180");
        }
        List<String> cambios = new ArrayList<>();
        anotar(cambios, "mostrarNombreFuncionario", this.mostrarNombreFuncionario, mostrarNombreFuncionario);
        anotar(cambios, "mostrarRechazados", this.mostrarRechazados, mostrarRechazados);
        anotar(cambios, "mesesHistorial", this.mesesHistorial, mesesHistorial);
        anotar(cambios, "diasPapelera", this.diasPapelera, diasPapelera);
        anotar(cambios, "alVencer", this.alVencer, alVencer);
        anotar(cambios, "auditorRestaura", this.auditorRestaura, auditorRestaura);
        this.mostrarNombreFuncionario = mostrarNombreFuncionario;
        this.mostrarRechazados = mostrarRechazados;
        this.mesesHistorial = mesesHistorial;
        this.diasPapelera = diasPapelera;
        this.alVencer = alVencer;
        this.auditorRestaura = auditorRestaura;
        return String.join(", ", cambios);
    }

    private static void anotar(List<String> cambios, String campo, Object antes, Object despues) {
        if (!antes.equals(despues)) cambios.add(campo + " " + antes + " -> " + despues);
    }

    public boolean isMostrarNombreFuncionario() { return mostrarNombreFuncionario; }
    public boolean isMostrarRechazados() { return mostrarRechazados; }
    public int getMesesHistorial() { return mesesHistorial; }
    public int getDiasPapelera() { return diasPapelera; }
    public AlVencer getAlVencer() { return alVencer; }
    public boolean isAuditorRestaura() { return auditorRestaura; }
}
