package com.banco.core.dto;

import java.time.LocalDateTime;
import java.util.List;

// Estado de un job para una fecha (su JobInstance): estado de la ultima ejecucion, cuantas ejecuciones hubo
// (la primera mas cada reanudacion, automatica o manual) y el detalle de cada una, de la mas antigua a la ultima.
public record EstadoBatchDTO(String job, String fecha, String estado, int intentos, List<Ejecucion> ejecuciones) {

    public record Ejecucion(Long id, String estado, String codigoSalida, LocalDateTime inicio, LocalDateTime fin) {
    }
}
