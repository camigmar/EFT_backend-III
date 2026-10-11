package com.banco.core.dto;

// reintentosAutomaticos: cuantas reanudaciones automaticas quedaron programadas (0 si el job no termino FAILED).
public record EjecucionBatchDTO(String job, String fecha, Long ejecucionId, String estado, String codigoSalida,
                                int reintentosAutomaticos) {
}
