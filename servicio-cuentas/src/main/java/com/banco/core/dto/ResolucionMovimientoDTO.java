package com.banco.core.dto;

// aplicado=true: el movimiento con esa clave se aplico (operacion, monto y saldo resultante).
// aplicado=false: no se habia aplicado y desde ahora queda anulado (nunca se aplicara con esa clave).
public record ResolucionMovimientoDTO(String clave, boolean aplicado, String operacion, Double monto, Double nuevoSaldo) {
}
