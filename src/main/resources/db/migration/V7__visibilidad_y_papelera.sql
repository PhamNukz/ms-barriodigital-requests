-- Admin B: visibilidad para el vecino y papelera (borrado logico; la fila no se borra).
-- motivo_visibilidad en CHAR: el resto de las columnas cuenta bytes, y un motivo de 200
-- caracteres con tildes pasaba de 200 bytes (ORA-12899).
ALTER TABLE tramite ADD (
    visible_vecino      NUMBER(1)           DEFAULT 1 NOT NULL,
    motivo_visibilidad  VARCHAR2(200 CHAR),
    eliminado_en        TIMESTAMP,
    eliminado_por       VARCHAR2(150)
);

-- Reglas del Admin: una sola fila (id = 1), con los valores por defecto del contrato.
CREATE TABLE regla_visibilidad (
    id                          NUMBER(19)    PRIMARY KEY,
    mostrar_nombre_funcionario  NUMBER(1)     NOT NULL,
    mostrar_rechazados          NUMBER(1)     NOT NULL,
    meses_historial             NUMBER(3)     NOT NULL,
    dias_papelera               NUMBER(4)     NOT NULL,
    al_vencer                   VARCHAR2(12)  NOT NULL,
    auditor_restaura            NUMBER(1)     NOT NULL,
    CONSTRAINT ck_regla_una_fila CHECK (id = 1)
);

INSERT INTO regla_visibilidad (id, mostrar_nombre_funcionario, mostrar_rechazados, meses_historial,
                               dias_papelera, al_vencer, auditor_restaura)
VALUES (1, 1, 1, 12, 90, 'ANONIMIZAR', 0);
