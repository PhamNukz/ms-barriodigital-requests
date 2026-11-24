-- Punto exacto que el vecino marca en el mapa, para que la cuadrilla llegue sin
-- buscar: en los cerros de Valparaiso la numeracion no basta. NUMBER(9,6) da
-- ~0,1 m de resolucion. Nullable: los tramites anteriores y los que se ingresan
-- sin mapa siguen valiendo solo con la direccion en texto.
-- referencia: cerro, pasaje, escalera, color de la casa.
ALTER TABLE tramite ADD (
    lat         NUMBER(9,6),
    lng         NUMBER(9,6),
    referencia  VARCHAR2(300)
);
