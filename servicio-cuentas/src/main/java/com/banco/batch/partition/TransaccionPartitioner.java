package com.banco.batch.partition;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.core.io.Resource;

public class TransaccionPartitioner implements Partitioner {

    private final Resource archivo;

    // El archivo viene de la propiedad batch.transacciones.archivo (por defecto el CSV del classpath).
    public TransaccionPartitioner(Resource archivo) {
        this.archivo = archivo;
    }

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        long totalFilas = contarFilasDeDatos();

        Map<String, ExecutionContext> particiones = new HashMap<>();
        long tamanioBase = totalFilas / gridSize;
        long resto = totalFilas % gridSize;

        long start = 0;
        for (int i = 0; i < gridSize; i++) {
            long tamanioParticion = tamanioBase + (i < resto ? 1 : 0);
            long end = start + tamanioParticion;

            ExecutionContext context = new ExecutionContext();
            context.putLong("start", start);
            context.putLong("end", end);
            particiones.put("partition" + i, context);

            start = end;
        }

        return particiones;
    }

    // Por stream y no con getFile(): dentro del jar el CSV del classpath no es un archivo del sistema.
    private long contarFilasDeDatos() {
        try (BufferedReader lector = new BufferedReader(
                new InputStreamReader(archivo.getInputStream(), StandardCharsets.UTF_8))) {
            return lector.lines().count() - 1;
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer " + archivo.getDescription(), e);
        }
    }
}
