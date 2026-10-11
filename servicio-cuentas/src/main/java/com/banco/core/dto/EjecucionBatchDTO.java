package com.banco.core.dto;

public record EjecucionBatchDTO(String job, String fecha, Long ejecucionId, String estado, String codigoSalida) {
}
