-- cuadrilla_asignada nunca se escribio: no habia setter ni asignacion en el codigo,
-- se serializaba siempre null en la API y el frontend ni la declaraba.
-- Verificado antes de borrar: 0 de 4 filas tenian valor.
-- Si EP2 implementa la asignacion de cuadrillas, se vuelve a agregar con su logica.
ALTER TABLE tramite DROP COLUMN cuadrilla_asignada;
