package com.banco.core.service;

import com.banco.batch.model.CuentaInteres;
import com.banco.batch.model.EstadoCuenta;
import com.banco.batch.model.EstadoCuentaAnual;
import com.banco.batch.repository.CuentaInteresRepository;
import com.banco.batch.repository.EstadoCuentaAnualRepository;
import com.banco.core.client.ClientesClient;
import com.banco.core.dto.ActualizarCuentaRequestDTO;
import com.banco.core.dto.AperturaCuentaRequestDTO;
import com.banco.core.dto.ClienteDTO;
import com.banco.core.dto.ResolucionMovimientoDTO;
import com.banco.core.event.RetiroEventPublisher;
import com.banco.core.event.RetiroRealizadoEvent;
import com.banco.core.exception.CierreNoPermitidoException;
import com.banco.core.exception.ClaveIdempotenciaReutilizadaException;
import com.banco.core.model.MovimientoIdempotente;
import com.banco.core.repository.MovimientoIdempotenteRepository;
import com.banco.core.exception.ClienteInactivoException;
import com.banco.core.exception.ClientesNoDisponibleException;
import com.banco.core.exception.CuentaCerradaException;
import com.banco.core.exception.CuentaNoEncontradaException;
import com.banco.core.exception.MontoInvalidoException;
import com.banco.core.exception.SaldoInsuficienteException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class CuentaService {

    private final CuentaInteresRepository cuentaInteresRepository;
    private final EstadoCuentaAnualRepository estadoCuentaAnualRepository;
    private final RetiroEventPublisher retiroEventPublisher;
    private final ClientesClient clientesClient;
    private final TransactionTemplate transactionTemplate;
    private final MovimientoIdempotenteRepository movimientoIdempotenteRepository;

    static final String OPERACION_DEBITO = "debito";
    static final String OPERACION_CREDITO = "credito";
    static final String OPERACION_ANULADO = "anulado";

    public CuentaService(CuentaInteresRepository cuentaInteresRepository,
                          EstadoCuentaAnualRepository estadoCuentaAnualRepository,
                          RetiroEventPublisher retiroEventPublisher,
                          ClientesClient clientesClient,
                          TransactionTemplate transactionTemplate,
                          MovimientoIdempotenteRepository movimientoIdempotenteRepository) {
        this.cuentaInteresRepository = cuentaInteresRepository;
        this.estadoCuentaAnualRepository = estadoCuentaAnualRepository;
        this.retiroEventPublisher = retiroEventPublisher;
        this.clientesClient = clientesClient;
        this.transactionTemplate = transactionTemplate;
        this.movimientoIdempotenteRepository = movimientoIdempotenteRepository;
    }

    // La consulta a servicio-clientes va fuera de la transaccion; solo el calculo del id y el
    // insert van dentro, con la cuenta de mayor id bloqueada para que dos aperturas no choquen.
    public CuentaInteres abrirCuenta(AperturaCuentaRequestDTO request) {
        ClienteDTO cliente = clientesClient.obtenerCliente(request.clienteId());
        if (cliente == null) {
            throw new ClientesNoDisponibleException("servicio-clientes respondio sin datos del cliente", null);
        }
        if ("INACTIVO".equals(cliente.estado())) {
            throw new ClienteInactivoException("El cliente " + request.clienteId() + " esta inactivo");
        }

        return transactionTemplate.execute(status -> {
            long siguienteId = cuentaInteresRepository.findFirstByOrderByCuentaIdDesc()
                    .map(ultima -> ultima.getCuentaId() + 1)
                    .orElse(1L);

            CuentaInteres cuenta = new CuentaInteres();
            cuenta.setCuentaId(siguienteId);
            cuenta.setClienteId(request.clienteId());
            cuenta.setNombre(request.nombre());
            cuenta.setEdad(request.edad());
            cuenta.setTipo(request.tipo());
            cuenta.setSaldo(request.saldoInicial());
            cuenta.setEstado(EstadoCuenta.ACTIVA);
            return cuentaInteresRepository.save(cuenta);
        });
    }

    @Transactional
    public CuentaInteres actualizarCuenta(Long cuentaId, ActualizarCuentaRequestDTO request) {
        CuentaInteres cuenta = buscarParaActualizar(cuentaId);
        verificarAbierta(cuenta);

        cuenta.setNombre(request.nombre());
        cuenta.setEdad(request.edad());
        cuenta.setTipo(request.tipo());
        return cuentaInteresRepository.save(cuenta);
    }

    @Transactional
    public CuentaInteres cerrarCuenta(Long cuentaId) {
        CuentaInteres cuenta = buscarParaActualizar(cuentaId);
        if (cuenta.estaCerrada()) {
            throw new CuentaCerradaException("La cuenta " + cuentaId + " ya esta cerrada");
        }
        double saldo = cuenta.getSaldo() != null ? cuenta.getSaldo() : 0.0;
        if (saldo != 0.0) {
            throw new CierreNoPermitidoException("No se puede cerrar la cuenta " + cuentaId
                    + ": el saldo debe ser 0 y es " + saldo);
        }

        cuenta.setEstado(EstadoCuenta.CERRADA);
        return cuentaInteresRepository.save(cuenta);
    }

    public List<CuentaInteres> listarPorCliente(Long clienteId) {
        return cuentaInteresRepository.findByClienteIdOrderByCuentaIdAsc(clienteId);
    }

    public Optional<CuentaInteres> obtenerCuenta(Long cuentaId) {
        return cuentaInteresRepository.findById(cuentaId);
    }

    public boolean existeCuenta(Long cuentaId) {
        return cuentaInteresRepository.existsById(cuentaId);
    }

    public Optional<EstadoCuentaAnual> obtenerEstadoAnual(Long cuentaId) {
        return estadoCuentaAnualRepository.findById(cuentaId);
    }

    public double retirar(Long cuentaId, Double monto) {
        if (monto == null || monto <= 0) {
            throw new MontoInvalidoException("El monto a retirar debe ser mayor a 0");
        }

        CuentaInteres cuenta = cuentaInteresRepository.findById(cuentaId)
                .orElseThrow(() -> new CuentaNoEncontradaException("Cuenta no encontrada"));
        verificarAbierta(cuenta);

        if (cuenta.getSaldo() == null || cuenta.getSaldo() < monto) {
            throw new SaldoInsuficienteException("Saldo insuficiente para realizar el retiro");
        }

        double nuevoSaldo = cuenta.getSaldo() - monto;
        cuenta.setSaldo(nuevoSaldo);
        cuentaInteresRepository.save(cuenta);
        retiroEventPublisher.publicar(
                new RetiroRealizadoEvent(cuentaId, monto, nuevoSaldo, LocalDateTime.now().toString()));

        return nuevoSaldo;
    }

    @Transactional
    public double debitar(Long cuentaId, Double monto) {
        return debitar(cuentaId, monto, null);
    }

    @Transactional
    public double acreditar(Long cuentaId, Double monto) {
        return acreditar(cuentaId, monto, null);
    }

    // Debito y credito para servicio-pagos: no publican evento (lo hace pagos) y leen la cuenta
    // con bloqueo pesimista dentro de la transaccion para evitar carreras sobre el saldo.
    // Con claveIdempotencia, un movimiento repetido (reintento tras un timeout) devuelve el
    // resultado original sin volver a mover el saldo.
    @Transactional
    public double debitar(Long cuentaId, Double monto, String claveIdempotencia) {
        validarMonto(monto);
        CuentaInteres cuenta = buscarParaActualizar(cuentaId);
        Optional<Double> previo = movimientoYaAplicado(claveIdempotencia, cuentaId, OPERACION_DEBITO, monto);
        if (previo.isPresent()) {
            return previo.get();
        }
        verificarAbierta(cuenta);

        if (cuenta.getSaldo() == null || cuenta.getSaldo() < monto) {
            throw new SaldoInsuficienteException("Saldo insuficiente para realizar el debito");
        }

        double nuevoSaldo = cuenta.getSaldo() - monto;
        cuenta.setSaldo(nuevoSaldo);
        cuentaInteresRepository.save(cuenta);
        registrarMovimiento(claveIdempotencia, cuentaId, OPERACION_DEBITO, monto, nuevoSaldo);
        return nuevoSaldo;
    }

    @Transactional
    public double acreditar(Long cuentaId, Double monto, String claveIdempotencia) {
        validarMonto(monto);
        CuentaInteres cuenta = buscarParaActualizar(cuentaId);
        Optional<Double> previo = movimientoYaAplicado(claveIdempotencia, cuentaId, OPERACION_CREDITO, monto);
        if (previo.isPresent()) {
            return previo.get();
        }
        verificarAbierta(cuenta);

        double saldoActual = cuenta.getSaldo() != null ? cuenta.getSaldo() : 0.0;
        double nuevoSaldo = saldoActual + monto;
        cuenta.setSaldo(nuevoSaldo);
        cuentaInteresRepository.save(cuenta);
        registrarMovimiento(claveIdempotencia, cuentaId, OPERACION_CREDITO, monto, nuevoSaldo);
        return nuevoSaldo;
    }

    // Para el reconciliador de servicio-pagos: dice si el movimiento con esta clave se aplico. Si no, lo anula
    // (marca "anulado" con la misma clave): un debito/credito atrasado que llegue despues con esa clave recibe 422
    // y nunca se aplica, asi la respuesta es definitiva. Bloquea la cuenta para serializarse con ese movimiento.
    @Transactional
    public ResolucionMovimientoDTO resolverMovimiento(String clave, Long cuentaId) {
        cuentaInteresRepository.findByIdForUpdate(cuentaId);
        Optional<MovimientoIdempotente> previo = movimientoIdempotenteRepository.findById(clave);
        if (previo.isPresent()) {
            MovimientoIdempotente m = previo.get();
            boolean aplicado = !OPERACION_ANULADO.equals(m.getOperacion());
            return new ResolucionMovimientoDTO(clave, aplicado, m.getOperacion(), m.getMonto(), m.getNuevoSaldo());
        }
        MovimientoIdempotente anulado = new MovimientoIdempotente();
        anulado.setClave(clave);
        anulado.setCuentaId(cuentaId);
        anulado.setOperacion(OPERACION_ANULADO);
        anulado.setMonto(0.0);
        anulado.setNuevoSaldo(0.0);
        anulado.setFecha(LocalDateTime.now());
        movimientoIdempotenteRepository.save(anulado);
        return new ResolucionMovimientoDTO(clave, false, OPERACION_ANULADO, 0.0, null);
    }

    // Se consulta despues de bloquear la cuenta: dos peticiones con la misma clave sobre la misma
    // cuenta se serializan en ese bloqueo y la segunda ya ve el movimiento de la primera.
    private Optional<Double> movimientoYaAplicado(String clave, Long cuentaId, String operacion, Double monto) {
        if (clave == null) {
            return Optional.empty();
        }
        return movimientoIdempotenteRepository.findById(clave).map(previo -> {
            if (!previo.getCuentaId().equals(cuentaId) || !previo.getOperacion().equals(operacion)
                    || Double.compare(previo.getMonto(), monto) != 0) {
                throw new ClaveIdempotenciaReutilizadaException("La Idempotency-Key " + clave
                        + " ya se uso para otro movimiento (" + previo.getOperacion() + " de " + previo.getMonto()
                        + " en la cuenta " + previo.getCuentaId() + ")");
            }
            return previo.getNuevoSaldo();
        });
    }

    private void registrarMovimiento(String clave, Long cuentaId, String operacion, Double monto, double nuevoSaldo) {
        if (clave == null) {
            return;
        }
        MovimientoIdempotente movimiento = new MovimientoIdempotente();
        movimiento.setClave(clave);
        movimiento.setCuentaId(cuentaId);
        movimiento.setOperacion(operacion);
        movimiento.setMonto(monto);
        movimiento.setNuevoSaldo(nuevoSaldo);
        movimiento.setFecha(LocalDateTime.now());
        movimientoIdempotenteRepository.save(movimiento);
    }

    // Lee la cuenta con bloqueo pesimista; debe llamarse dentro de una transaccion.
    private CuentaInteres buscarParaActualizar(Long cuentaId) {
        return cuentaInteresRepository.findByIdForUpdate(cuentaId)
                .orElseThrow(() -> new CuentaNoEncontradaException("Cuenta no encontrada"));
    }

    private void verificarAbierta(CuentaInteres cuenta) {
        if (cuenta.estaCerrada()) {
            throw new CuentaCerradaException("La cuenta " + cuenta.getCuentaId() + " esta cerrada");
        }
    }

    private void validarMonto(Double monto) {
        if (monto == null || monto <= 0) {
            throw new MontoInvalidoException("El monto debe ser mayor a 0");
        }
    }
}
