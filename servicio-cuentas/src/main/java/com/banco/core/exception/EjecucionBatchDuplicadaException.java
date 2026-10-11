package com.banco.core.exception;

// El job ya se ejecuto, esta en ejecucion o lo esta iniciando otra replica para esos parametros: 409.
public class EjecucionBatchDuplicadaException extends RuntimeException {

    public EjecucionBatchDuplicadaException(String message) {
        super(message);
    }
}
