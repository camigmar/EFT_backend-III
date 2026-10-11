package com.banco.batch.processor;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import com.banco.batch.model.CuentaInteres;

public class InteresProcessor implements ItemProcessor<CuentaInteres, CuentaInteres> {

    // Concurrente: lo comparten los hilos del step (batch.intereses.hilos). Cual de dos filas duplicadas del CSV
    // se conserva depende del orden en que las procesen los hilos.
    private final Set<String> vistos = ConcurrentHashMap.newKeySet();

    private static final double TASA_AHORRO = 0.02;
    private static final double TASA_PRESTAMO = 0.05;
    private static final double TASA_HIPOTECA = 0.03;

    @Override
    public CuentaInteres process(CuentaInteres c) {
        if (c == null || c.getSaldo() == null || c.getSaldo() <= 0) {
            return null;
        }
        if (c.getEdad() == null || c.getEdad() < 18 || c.getEdad() > 100) {
            return null;
        }
        if (c.getTipo() == null) {
            return null;
        }

        String tipo = c.getTipo().trim().toLowerCase();
        c.setTipo(tipo);

        String clave = c.getNombre() + "-" + c.getSaldo() + "-" + c.getEdad() + "-" + tipo;
        // add() atomico: el step procesa con varios hilos; "contains y despues add" dejaba pasar los dos duplicados.
        if (!vistos.add(clave)) {
            return null;
        }

        double tasa;
        switch (tipo) {
            case "ahorro" -> tasa = TASA_AHORRO;
            case "prestamo" -> tasa = TASA_PRESTAMO;
            case "hipoteca" -> tasa = TASA_HIPOTECA;
            default -> {
                return null;
            }
        }

        c.setSaldoFinal(c.getSaldo() * (1 + tasa));
        return c;
    }
}
