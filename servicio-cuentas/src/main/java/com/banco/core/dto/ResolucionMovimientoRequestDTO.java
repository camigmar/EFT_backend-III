package com.banco.core.dto;

import jakarta.validation.constraints.NotNull;

// Cuenta del movimiento: se bloquea para que la resolucion no se cruce con un debito/credito en curso.
public record ResolucionMovimientoRequestDTO(@NotNull(message = "La cuenta es obligatoria") Long cuentaId) {
}
