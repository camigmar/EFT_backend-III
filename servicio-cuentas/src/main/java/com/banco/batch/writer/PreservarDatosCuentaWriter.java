package com.banco.batch.writer;

import com.banco.batch.model.CuentaInteres;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;

// El job de intereses arma cada CuentaInteres desde el CSV y el JpaItemWriter hace merge, que copia todos
// los campos sobre la fila existente. Antes de delegar, este writer conserva los datos vivos de las cuentas
// que ya existen, para que reejecutar el job no corrompa nada:
// - saldo: el saldo real (movido por retiros, pagos y transferencias), no el del CSV;
// - estado y clienteId: no reabre una cuenta CERRADA ni la desvincula de su cliente;
// - saldoFinal: la proyeccion con interes se recalcula sobre el saldo real, con la tasa que aplico el processor.
// Las cuentas nuevas se insertan tal como vienen del CSV.
public class PreservarDatosCuentaWriter implements ItemWriter<CuentaInteres> {

    private final EntityManagerFactory entityManagerFactory;
    private final ItemWriter<CuentaInteres> delegado;

    public PreservarDatosCuentaWriter(EntityManagerFactory entityManagerFactory, ItemWriter<CuentaInteres> delegado) {
        this.entityManagerFactory = entityManagerFactory;
        this.delegado = delegado;
    }

    @Override
    public void write(Chunk<? extends CuentaInteres> items) throws Exception {
        // Mismo EntityManager transaccional que usa el JpaItemWriter dentro de la transaccion del chunk.
        EntityManager entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
        if (entityManager == null) {
            throw new IllegalStateException("No hay una transaccion activa para escribir el chunk");
        }
        for (CuentaInteres item : items) {
            CuentaInteres existente = entityManager.find(CuentaInteres.class, item.getCuentaId());
            if (existente != null) {
                preservarDatosVivos(item, existente);
            }
        }
        delegado.write(items);
    }

    static void preservarDatosVivos(CuentaInteres item, CuentaInteres existente) {
        item.setEstado(existente.getEstado());
        item.setClienteId(existente.getClienteId());
        if (existente.getSaldo() != null) {
            // El processor solo deja pasar saldos > 0, asi que saldoFinal / saldo es (1 + tasa).
            double factorInteres = item.getSaldoFinal() / item.getSaldo();
            item.setSaldo(existente.getSaldo());
            item.setSaldoFinal(existente.getSaldo() * factorInteres);
        }
    }
}
