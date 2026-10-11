package com.banco.batch.writer;

import com.banco.batch.model.CuentaInteres;
import com.banco.batch.model.EstadoCuenta;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PreservarDatosCuentaWriterTest {

    @Test
    void cuentaExistente_conservaSaldoEstadoYClienteYRecalculaElInteresSobreElSaldoReal() {
        // Del CSV: saldo 5000, ahorro (2%) -> saldoFinal 5100.
        CuentaInteres delCsv = cuenta(101L, 5000.0, 5100.0);
        // En la BD: saldo real 3000 tras retiros/pagos, cuenta cerrada y asociada al cliente 7.
        CuentaInteres existente = cuenta(101L, 3000.0, 5100.0);
        existente.setEstado(EstadoCuenta.CERRADA);
        existente.setClienteId(7L);

        PreservarDatosCuentaWriter.preservarDatosVivos(delCsv, existente);

        assertThat(delCsv.getSaldo()).isEqualTo(3000.0);
        assertThat(delCsv.getSaldoFinal()).isCloseTo(3060.0, within(0.0001));
        assertThat(delCsv.getEstado()).isEqualTo(EstadoCuenta.CERRADA);
        assertThat(delCsv.getClienteId()).isEqualTo(7L);
    }

    private CuentaInteres cuenta(Long id, Double saldo, Double saldoFinal) {
        CuentaInteres cuenta = new CuentaInteres();
        cuenta.setCuentaId(id);
        cuenta.setSaldo(saldo);
        cuenta.setSaldoFinal(saldoFinal);
        cuenta.setTipo("ahorro");
        return cuenta;
    }
}
