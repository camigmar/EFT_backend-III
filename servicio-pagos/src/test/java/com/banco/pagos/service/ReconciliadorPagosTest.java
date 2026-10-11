package com.banco.pagos.service;

import com.banco.pagos.client.CuentasClient;
import com.banco.pagos.event.PagoEventPublisher;
import com.banco.pagos.event.TipoAlerta;
import com.banco.pagos.exception.CuentasNoDisponibleException;
import com.banco.pagos.exception.OperacionRechazadaException;
import com.banco.pagos.model.EstadoPago;
import com.banco.pagos.model.Pago;
import com.banco.pagos.model.TipoPago;
import com.banco.pagos.repository.PagoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconciliadorPagosTest {

    @Mock
    private PagoRepository pagoRepository;

    @Mock
    private CuentasClient cuentasClient;

    @Mock
    private PagoEventPublisher eventPublisher;

    private ReconciliadorPagos reconciliador;

    @BeforeEach
    void setUp() {
        reconciliador = new ReconciliadorPagos(pagoRepository, cuentasClient, eventPublisher, Duration.ofSeconds(60));
    }

    private Pago pago(TipoPago tipo, EstadoPago estado, int status) {
        Pago pago = new Pago();
        pago.setId(7L);
        pago.setTipo(tipo);
        pago.setEstado(estado);
        pago.setStatusRespuesta(status);
        pago.setCuentaOrigen(101L);
        pago.setCuentaDestino(tipo == TipoPago.TRANSFERENCIA ? 102L : null);
        pago.setMonto(10.0);
        pago.setFecha(LocalDateTime.now());
        pago.setMotivo("servicio-cuentas no esta disponible");
        return pago;
    }

    private void aplicados(boolean debito, boolean credito, boolean devolucion) {
        when(cuentasClient.movimientoAplicado("pago-7-debito", 101L)).thenReturn(debito);
        if (debito) {
            when(cuentasClient.movimientoAplicado("pago-7-credito", 102L)).thenReturn(credito);
            when(cuentasClient.movimientoAplicado("pago-7-compensacion", 101L)).thenReturn(devolucion);
        }
    }

    @Test
    void retiroFallidoCuyoDebitoSiSeAplico_seRevierteYQuedaFallido() {
        Pago pago = pago(TipoPago.RETIRO, EstadoPago.FALLIDO, 503);
        when(cuentasClient.movimientoAplicado("pago-7-debito", 101L)).thenReturn(true);

        reconciliador.reconciliar(pago);

        verify(cuentasClient).acreditar(101L, 10.0, "pago-7-reversion");
        assertThat(pago.getEstado()).isEqualTo(EstadoPago.FALLIDO);
        assertThat(pago.getReconciliado()).isTrue();
        assertThat(pago.getMotivo()).contains("se revirtio");
    }

    @Test
    void depositoFallidoQueNoSeAplico_quedaFallidoSinMovimientos() {
        Pago pago = pago(TipoPago.DEPOSITO, EstadoPago.FALLIDO, 503);
        when(cuentasClient.movimientoAplicado("pago-7-credito", 101L)).thenReturn(false);

        reconciliador.reconciliar(pago);

        verify(cuentasClient, never()).debitar(any(), any(), any());
        verify(cuentasClient, never()).acreditar(any(), any(), any());
        assertThat(pago.getEstado()).isEqualTo(EstadoPago.FALLIDO);
        assertThat(pago.getReconciliado()).isTrue();
    }

    @Test
    void depositoPendienteYaAplicado_seCompleta() {
        Pago pago = pago(TipoPago.DEPOSITO, EstadoPago.PENDIENTE, 0);
        pago.setStatusRespuesta(null);
        when(cuentasClient.movimientoAplicado("pago-7-credito", 101L)).thenReturn(true);

        reconciliador.reconciliar(pago);

        assertThat(pago.getEstado()).isEqualTo(EstadoPago.COMPLETADO);
        assertThat(pago.getStatusRespuesta()).isEqualTo(201);
        verify(eventPublisher).publicarTransaccionCompletada(pago);
    }

    @Test
    void transferenciaEnRevisionConCreditoAplicado_quedaCompletada() {
        Pago pago = pago(TipoPago.TRANSFERENCIA, EstadoPago.REQUIERE_REVISION, 500);
        aplicados(true, true, false);

        reconciliador.reconciliar(pago);

        assertThat(pago.getEstado()).isEqualTo(EstadoPago.COMPLETADO);
        verify(cuentasClient, never()).debitar(any(), any(), anyString());
        verify(cuentasClient, never()).acreditar(any(), any(), anyString());
        verify(eventPublisher).publicarTransaccionCompletada(pago);
    }

    @Test
    void transferenciaEnRevisionSinCreditoNiDevolucion_devuelveElMontoYQuedaCompensada() {
        Pago pago = pago(TipoPago.TRANSFERENCIA, EstadoPago.REQUIERE_REVISION, 500);
        aplicados(true, false, false);

        reconciliador.reconciliar(pago);

        verify(cuentasClient).acreditar(101L, 10.0, "pago-7-reversion");
        assertThat(pago.getEstado()).isEqualTo(EstadoPago.COMPENSADO);
        verify(eventPublisher).publicarAlerta(eq(TipoAlerta.COMPENSACION_EJECUTADA), eq(pago), anyString());
    }

    @Test
    void transferenciaCompensadaCuyoCreditoTambienSeAplico_deshaceLaDevolucionYQuedaCompletada() {
        Pago pago = pago(TipoPago.TRANSFERENCIA, EstadoPago.COMPENSADO, 503);
        aplicados(true, true, true);

        reconciliador.reconciliar(pago);

        verify(cuentasClient).debitar(101L, 10.0, "pago-7-reversion");
        assertThat(pago.getEstado()).isEqualTo(EstadoPago.COMPLETADO);
    }

    @Test
    void transferenciaFallidaSinDebito_quedaFallidaSinConsultarElCredito() {
        Pago pago = pago(TipoPago.TRANSFERENCIA, EstadoPago.FALLIDO, 503);
        aplicados(false, false, false);

        reconciliador.reconciliar(pago);

        verify(cuentasClient, never()).movimientoAplicado(eq("pago-7-credito"), any());
        assertThat(pago.getEstado()).isEqualTo(EstadoPago.FALLIDO);
        assertThat(pago.getReconciliado()).isTrue();
    }

    @Test
    void cuentasSinResponder_dejaElPagoParaLaProximaPasada() {
        Pago pago = pago(TipoPago.TRANSFERENCIA, EstadoPago.REQUIERE_REVISION, 500);
        when(pagoRepository.buscarPorReconciliar(any(), any(), any(), any())).thenReturn(List.of(pago));
        when(cuentasClient.movimientoAplicado("pago-7-debito", 101L))
                .thenThrow(new CuentasNoDisponibleException("servicio-cuentas no esta disponible", null));

        reconciliador.reconciliarPendientes();

        assertThat(pago.getEstado()).isEqualTo(EstadoPago.REQUIERE_REVISION);
        assertThat(pago.getReconciliado()).isNull();
        verify(pagoRepository, never()).save(any());
    }

    @Test
    void reversionRechazadaPorCuentas_quedaParaIntervencionManual() {
        Pago pago = pago(TipoPago.DEPOSITO, EstadoPago.FALLIDO, 503);
        when(pagoRepository.buscarPorReconciliar(any(), any(), any(), any())).thenReturn(List.of(pago));
        when(cuentasClient.movimientoAplicado("pago-7-credito", 101L)).thenReturn(true);
        when(cuentasClient.debitar(101L, 10.0, "pago-7-reversion"))
                .thenThrow(new OperacionRechazadaException("Saldo insuficiente para realizar el debito"));

        reconciliador.reconciliarPendientes();

        assertThat(pago.getEstado()).isEqualTo(EstadoPago.REQUIERE_REVISION);
        assertThat(pago.getReconciliado()).isTrue();
        assertThat(pago.getMotivo()).contains("intervencion manual");
    }
}
