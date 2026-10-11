package com.banco.pagos.service;

import com.banco.pagos.client.CuentasClient;
import com.banco.pagos.event.PagoEventPublisher;
import com.banco.pagos.event.TipoAlerta;
import com.banco.pagos.exception.CuentasException;
import com.banco.pagos.exception.CuentasNoDisponibleException;
import com.banco.pagos.model.EstadoPago;
import com.banco.pagos.model.Pago;
import com.banco.pagos.model.TipoPago;
import com.banco.pagos.repository.PagoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static com.banco.pagos.service.PagoService.OPERACION_COMPENSACION;
import static com.banco.pagos.service.PagoService.OPERACION_CREDITO;
import static com.banco.pagos.service.PagoService.OPERACION_DEBITO;
import static com.banco.pagos.service.PagoService.claveMovimiento;

// Cierra los pagos de resultado incierto: un movimiento enviado a servicio-cuentas que dio timeout o quedo sin
// respuesta (cuentas caido, circuito abierto) pudo aplicarse o no. Para cada movimiento del pago pregunta a cuentas
// por su Idempotency-Key ("pago-<id>-<operacion>"); si no se aplico, cuentas lo anula para siempre, asi que la
// respuesta es definitiva aunque la peticion original llegue despues. Con eso deja el dinero cuadrado:
// - retiro/deposito FALLIDO cuyo movimiento si se aplico: se revierte (el cliente recibio "fallido");
// - PENDIENTE (replica caida a mitad de la saga) cuyo movimiento se aplico: se completa;
// - transferencia: segun si se aplicaron el debito, el credito y la devolucion, queda COMPLETADO o COMPENSADO,
//   deshaciendo la devolucion si tambien se aplico el credito.
// Corre en todas las replicas: todos los movimientos son idempotentes, asi que si dos replicas reconcilian el mismo
// pago a la vez, llegan al mismo resultado sin mover dinero dos veces.
@Component
public class ReconciliadorPagos {

    static final String OPERACION_REVERSION = "reversion";

    private static final Logger logger = LoggerFactory.getLogger(ReconciliadorPagos.class);

    private final PagoRepository pagoRepository;
    private final CuentasClient cuentasClient;
    private final PagoEventPublisher eventPublisher;
    private final Duration pendienteMinimo;

    public ReconciliadorPagos(PagoRepository pagoRepository, CuentasClient cuentasClient, PagoEventPublisher eventPublisher,
                              @Value("${reconciliacion.pendiente-minimo:60s}") Duration pendienteMinimo) {
        this.pagoRepository = pagoRepository;
        this.cuentasClient = cuentasClient;
        this.eventPublisher = eventPublisher;
        this.pendienteMinimo = pendienteMinimo;
    }

    @Scheduled(fixedDelayString = "${reconciliacion.intervalo:10s}", initialDelayString = "${reconciliacion.intervalo:10s}")
    public void reconciliarPendientes() {
        List<Pago> inciertos = pagoRepository.buscarPorReconciliar(EstadoPago.REQUIERE_REVISION,
                List.of(EstadoPago.FALLIDO, EstadoPago.COMPENSADO), EstadoPago.PENDIENTE,
                LocalDateTime.now().minus(pendienteMinimo));
        for (Pago pago : inciertos) {
            try {
                reconciliar(pago);
            } catch (CuentasNoDisponibleException ex) {
                // Cuentas sigue sin responder: este y los demas pagos quedan para la proxima pasada.
                logger.warn("Reconciliacion pospuesta (pago {}): {}", pago.getId(), ex.getMessage());
                return;
            } catch (CuentasException ex) {
                // Cuentas rechazo la reversion (por ejemplo, saldo insuficiente para deshacer un deposito).
                logger.error("Pago {} requiere intervencion manual: {}", pago.getId(), ex.getMessage());
                cerrar(pago, EstadoPago.REQUIERE_REVISION, HttpStatus.INTERNAL_SERVER_ERROR,
                        "no se pudo revertir: " + ex.getMessage() + "; requiere intervencion manual");
            }
        }
    }

    void reconciliar(Pago pago) {
        switch (pago.getTipo()) {
            case RETIRO -> reconciliarMovimientoSimple(pago, OPERACION_DEBITO);
            case DEPOSITO -> reconciliarMovimientoSimple(pago, OPERACION_CREDITO);
            case TRANSFERENCIA -> reconciliarTransferencia(pago);
        }
    }

    private void reconciliarMovimientoSimple(Pago pago, String operacion) {
        boolean aplicado = cuentasClient.movimientoAplicado(claveMovimiento(pago, operacion), pago.getCuentaOrigen());
        if (!aplicado) {
            cerrar(pago, EstadoPago.FALLIDO, null, "el " + operacion + " no se aplico");
        } else if (pago.getEstado() == EstadoPago.PENDIENTE) {
            // Nadie recibio una respuesta final: se completa el pago, el movimiento ya esta hecho.
            cerrar(pago, EstadoPago.COMPLETADO, HttpStatus.CREATED, "el " + operacion + " se habia aplicado");
        } else {
            // El cliente recibio "fallido": se deshace el movimiento que si se aplico.
            String claveReversion = claveMovimiento(pago, OPERACION_REVERSION);
            if (pago.getTipo() == TipoPago.RETIRO) {
                cuentasClient.acreditar(pago.getCuentaOrigen(), pago.getMonto(), claveReversion);
            } else {
                cuentasClient.debitar(pago.getCuentaOrigen(), pago.getMonto(), claveReversion);
            }
            cerrar(pago, EstadoPago.FALLIDO, null, "el " + operacion + " se habia aplicado y se revirtio");
        }
    }

    private void reconciliarTransferencia(Pago pago) {
        if (!cuentasClient.movimientoAplicado(claveMovimiento(pago, OPERACION_DEBITO), pago.getCuentaOrigen())) {
            // Sin debito no hubo credito (la saga se detiene ahi): no se movio dinero.
            cerrar(pago, EstadoPago.FALLIDO, null, "el debito no se aplico");
            return;
        }
        boolean credito = cuentasClient.movimientoAplicado(claveMovimiento(pago, OPERACION_CREDITO), pago.getCuentaDestino());
        boolean devolucion = cuentasClient.movimientoAplicado(claveMovimiento(pago, OPERACION_COMPENSACION), pago.getCuentaOrigen());
        String claveReversion = claveMovimiento(pago, OPERACION_REVERSION);

        if (credito && !devolucion) {
            cerrar(pago, EstadoPago.COMPLETADO, HttpStatus.CREATED, "el credito se habia aplicado");
        } else if (credito) {
            // Se aplicaron el credito y la devolucion: el origen recupero un dinero que si llego al destino.
            cuentasClient.debitar(pago.getCuentaOrigen(), pago.getMonto(), claveReversion);
            cerrar(pago, EstadoPago.COMPLETADO, HttpStatus.CREATED, "el credito y la devolucion se habian aplicado; se deshizo la devolucion");
        } else if (devolucion) {
            cerrar(pago, EstadoPago.COMPENSADO, null, "el credito no se aplico y la devolucion si");
        } else {
            cuentasClient.acreditar(pago.getCuentaOrigen(), pago.getMonto(), claveReversion);
            cerrar(pago, EstadoPago.COMPENSADO, null, "ni el credito ni la devolucion se habian aplicado; se devolvio el monto al origen");
        }
    }

    // status null: se mantiene el que tenia el pago (la respuesta que recibio el cliente).
    private void cerrar(Pago pago, EstadoPago estado, HttpStatus status, String detalle) {
        EstadoPago anterior = pago.getEstado();
        pago.setEstado(estado);
        if (status != null) {
            pago.setStatusRespuesta(status.value());
        }
        String motivoPrevio = pago.getMotivo() != null ? pago.getMotivo() + " | " : "";
        pago.setMotivo(motivoPrevio + "Reconciliado: " + detalle);
        pago.setReconciliado(true);
        pagoRepository.save(pago);
        logger.info("Pago {} reconciliado: {} -> {} ({})", pago.getId(), anterior, estado, detalle);

        try {
            if (estado == EstadoPago.COMPLETADO && anterior != EstadoPago.COMPLETADO) {
                eventPublisher.publicarTransaccionCompletada(pago);
            } else if (estado == EstadoPago.COMPENSADO && anterior != EstadoPago.COMPENSADO) {
                eventPublisher.publicarAlerta(TipoAlerta.COMPENSACION_EJECUTADA, pago, pago.getMotivo());
            }
        } catch (RuntimeException ex) {
            logger.error("Fallo la publicacion del evento del pago reconciliado {}: {}", pago.getId(), ex.getMessage());
        }
    }
}
