package com.banco.core.exception;

// El job nunca se ejecuto para esa fecha (no hay JobInstance que consultar o reanudar): 404.
public class InstanciaBatchNoEncontradaException extends RuntimeException {

    public InstanciaBatchNoEncontradaException(String message) {
        super(message);
    }
}
