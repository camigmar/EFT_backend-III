package com.banco.pagos.dto;

// Respuesta de servicio-cuentas a POST /api/core/movimientos/{clave}/resolucion: si el movimiento con esa clave
// se aplico. Si no, cuentas lo deja anulado y ya no se aplicara aunque llegue una peticion atrasada.
public record ResolucionMovimientoDTO(String clave, boolean aplicado, String operacion, Double monto, Double nuevoSaldo) {
}
