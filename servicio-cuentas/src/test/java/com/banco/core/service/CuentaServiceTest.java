package com.banco.core.service;

import com.banco.batch.model.CuentaInteres;
import com.banco.batch.model.EstadoCuenta;
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
import com.banco.core.exception.ClienteNoEncontradoException;
import com.banco.core.exception.ClientesNoDisponibleException;
import com.banco.core.exception.CuentaCerradaException;
import com.banco.core.exception.CuentaNoEncontradaException;
import com.banco.core.exception.MontoInvalidoException;
import com.banco.core.exception.SaldoInsuficienteException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CuentaServiceTest {

    @Mock
    private CuentaInteresRepository cuentaInteresRepository;

    @Mock
    private EstadoCuentaAnualRepository estadoCuentaAnualRepository;

    @Mock
    private RetiroEventPublisher retiroEventPublisher;

    @Mock
    private ClientesClient clientesClient;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private MovimientoIdempotenteRepository movimientoIdempotenteRepository;

    @InjectMocks
    private CuentaService cuentaService;

    @Test
    void retirar_montoNuloLanzaMontoInvalido() {
        assertThrows(MontoInvalidoException.class, () -> cuentaService.retirar(101L, null));
        verifyNoInteractions(cuentaInteresRepository);
        verifyNoInteractions(retiroEventPublisher);
    }

    @Test
    void retirar_montoCeroONegativoLanzaMontoInvalido() {
        assertThrows(MontoInvalidoException.class, () -> cuentaService.retirar(101L, 0.0));
        assertThrows(MontoInvalidoException.class, () -> cuentaService.retirar(101L, -50.0));
        verifyNoInteractions(cuentaInteresRepository);
        verifyNoInteractions(retiroEventPublisher);
    }

    @Test
    void retirar_cuentaNoEncontradaLanzaExcepcion() {
        when(cuentaInteresRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(CuentaNoEncontradaException.class, () -> cuentaService.retirar(999L, 100.0));
        verifyNoInteractions(retiroEventPublisher);
    }

    @Test
    void retirar_saldoInsuficienteLanzaExcepcion() {
        CuentaInteres cuenta = new CuentaInteres();
        cuenta.setCuentaId(101L);
        cuenta.setSaldo(50.0);
        when(cuentaInteresRepository.findById(101L)).thenReturn(Optional.of(cuenta));

        assertThrows(SaldoInsuficienteException.class, () -> cuentaService.retirar(101L, 100.0));
        verify(cuentaInteresRepository, never()).save(any());
        verifyNoInteractions(retiroEventPublisher);
    }

    @Test
    void retirar_casoExitosoDescuentaYGuardaElNuevoSaldo() {
        CuentaInteres cuenta = new CuentaInteres();
        cuenta.setCuentaId(101L);
        cuenta.setSaldo(500.0);
        when(cuentaInteresRepository.findById(101L)).thenReturn(Optional.of(cuenta));

        double nuevoSaldo = cuentaService.retirar(101L, 200.0);

        assertThat(nuevoSaldo).isEqualTo(300.0);
        assertThat(cuenta.getSaldo()).isEqualTo(300.0);
        verify(cuentaInteresRepository).save(cuenta);

        ArgumentCaptor<RetiroRealizadoEvent> captor = ArgumentCaptor.forClass(RetiroRealizadoEvent.class);
        verify(retiroEventPublisher).publicar(captor.capture());
        RetiroRealizadoEvent evento = captor.getValue();
        assertThat(evento.cuentaId()).isEqualTo(101L);
        assertThat(evento.monto()).isEqualTo(200.0);
        assertThat(evento.nuevoSaldo()).isEqualTo(300.0);
    }

    @Test
    void debitar_casoExitosoDescuentaGuardaYNoPublicaEvento() {
        CuentaInteres cuenta = new CuentaInteres();
        cuenta.setCuentaId(101L);
        cuenta.setSaldo(500.0);
        when(cuentaInteresRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(cuenta));

        double nuevoSaldo = cuentaService.debitar(101L, 200.0);

        assertThat(nuevoSaldo).isEqualTo(300.0);
        assertThat(cuenta.getSaldo()).isEqualTo(300.0);
        verify(cuentaInteresRepository).save(cuenta);
        verifyNoInteractions(retiroEventPublisher);
    }

    @Test
    void debitar_montoInvalidoLanzaExcepcion() {
        assertThrows(MontoInvalidoException.class, () -> cuentaService.debitar(101L, null));
        assertThrows(MontoInvalidoException.class, () -> cuentaService.debitar(101L, 0.0));
        assertThrows(MontoInvalidoException.class, () -> cuentaService.debitar(101L, -10.0));
        verifyNoInteractions(cuentaInteresRepository);
    }

    @Test
    void debitar_cuentaNoEncontradaLanzaExcepcion() {
        when(cuentaInteresRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThrows(CuentaNoEncontradaException.class, () -> cuentaService.debitar(999L, 100.0));
        verify(cuentaInteresRepository, never()).save(any());
    }

    @Test
    void debitar_saldoInsuficienteLanzaExcepcionYNoGuarda() {
        CuentaInteres cuenta = new CuentaInteres();
        cuenta.setCuentaId(101L);
        cuenta.setSaldo(50.0);
        when(cuentaInteresRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(cuenta));

        assertThrows(SaldoInsuficienteException.class, () -> cuentaService.debitar(101L, 100.0));
        assertThat(cuenta.getSaldo()).isEqualTo(50.0);
        verify(cuentaInteresRepository, never()).save(any());
    }

    @Test
    void acreditar_casoExitosoSumaGuardaYNoPublicaEvento() {
        CuentaInteres cuenta = new CuentaInteres();
        cuenta.setCuentaId(102L);
        cuenta.setSaldo(100.0);
        when(cuentaInteresRepository.findByIdForUpdate(102L)).thenReturn(Optional.of(cuenta));

        double nuevoSaldo = cuentaService.acreditar(102L, 250.0);

        assertThat(nuevoSaldo).isEqualTo(350.0);
        assertThat(cuenta.getSaldo()).isEqualTo(350.0);
        verify(cuentaInteresRepository).save(cuenta);
        verifyNoInteractions(retiroEventPublisher);
    }

    @Test
    void acreditar_montoInvalidoLanzaExcepcion() {
        assertThrows(MontoInvalidoException.class, () -> cuentaService.acreditar(102L, null));
        assertThrows(MontoInvalidoException.class, () -> cuentaService.acreditar(102L, 0.0));
        verifyNoInteractions(cuentaInteresRepository);
    }

    @Test
    void acreditar_cuentaNoEncontradaLanzaExcepcion() {
        when(cuentaInteresRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThrows(CuentaNoEncontradaException.class, () -> cuentaService.acreditar(999L, 100.0));
        verify(cuentaInteresRepository, never()).save(any());
    }

    @Test
    void abrirCuenta_casoExitosoAsignaSiguienteIdActivaYCliente() {
        when(clientesClient.obtenerCliente(7L)).thenReturn(new ClienteDTO(7L, "ACTIVO"));
        ejecutarTransaccionesDirecto();
        when(cuentaInteresRepository.findFirstByOrderByCuentaIdDesc()).thenReturn(Optional.of(cuenta(108L, 100.0)));
        when(cuentaInteresRepository.save(any(CuentaInteres.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        CuentaInteres cuenta = cuentaService.abrirCuenta(aperturaPara(7L));

        assertThat(cuenta.getCuentaId()).isEqualTo(109L);
        assertThat(cuenta.getClienteId()).isEqualTo(7L);
        assertThat(cuenta.getEstado()).isEqualTo(EstadoCuenta.ACTIVA);
        assertThat(cuenta.getSaldo()).isEqualTo(1000.0);
        assertThat(cuenta.getTipo()).isEqualTo("ahorro");
    }

    @Test
    void abrirCuenta_clienteInexistenteNoAbreLaCuenta() {
        when(clientesClient.obtenerCliente(99L)).thenThrow(new ClienteNoEncontradoException("El cliente 99 no existe"));

        assertThrows(ClienteNoEncontradoException.class, () -> cuentaService.abrirCuenta(aperturaPara(99L)));
        verifyNoInteractions(transactionTemplate);
        verifyNoInteractions(cuentaInteresRepository);
    }

    @Test
    void abrirCuenta_clienteInactivoLanzaExcepcion() {
        when(clientesClient.obtenerCliente(7L)).thenReturn(new ClienteDTO(7L, "INACTIVO"));

        assertThrows(ClienteInactivoException.class, () -> cuentaService.abrirCuenta(aperturaPara(7L)));
        verifyNoInteractions(transactionTemplate);
        verifyNoInteractions(cuentaInteresRepository);
    }

    @Test
    void abrirCuenta_servicioClientesCaidoLanzaNoDisponible() {
        when(clientesClient.obtenerCliente(7L))
                .thenThrow(new ClientesNoDisponibleException("servicio-clientes no esta disponible", null));

        assertThrows(ClientesNoDisponibleException.class, () -> cuentaService.abrirCuenta(aperturaPara(7L)));
        verifyNoInteractions(cuentaInteresRepository);
    }

    @Test
    void cerrarCuenta_conSaldoLanzaExcepcionYNoCierra() {
        CuentaInteres cuenta = cuenta(101L, 50.0);
        when(cuentaInteresRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(cuenta));

        assertThrows(CierreNoPermitidoException.class, () -> cuentaService.cerrarCuenta(101L));
        assertThat(cuenta.getEstado()).isEqualTo(EstadoCuenta.ACTIVA);
        verify(cuentaInteresRepository, never()).save(any());
    }

    @Test
    void cerrarCuenta_conSaldoCeroQuedaCerrada() {
        CuentaInteres cuenta = cuenta(101L, 0.0);
        when(cuentaInteresRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(cuenta));
        when(cuentaInteresRepository.save(cuenta)).thenReturn(cuenta);

        CuentaInteres cerrada = cuentaService.cerrarCuenta(101L);

        assertThat(cerrada.getEstado()).isEqualTo(EstadoCuenta.CERRADA);
        verify(cuentaInteresRepository).save(cuenta);
    }

    @Test
    void cerrarCuenta_yaCerradaLanzaExcepcion() {
        CuentaInteres cuenta = cuenta(101L, 0.0);
        cuenta.setEstado(EstadoCuenta.CERRADA);
        when(cuentaInteresRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(cuenta));

        assertThrows(CuentaCerradaException.class, () -> cuentaService.cerrarCuenta(101L));
        verify(cuentaInteresRepository, never()).save(any());
    }

    @Test
    void actualizarCuenta_casoExitosoCambiaDatosSinTocarSaldoNiCliente() {
        CuentaInteres cuenta = cuenta(101L, 500.0);
        cuenta.setClienteId(7L);
        when(cuentaInteresRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(cuenta));
        when(cuentaInteresRepository.save(cuenta)).thenReturn(cuenta);

        CuentaInteres actualizada = cuentaService.actualizarCuenta(101L,
                new ActualizarCuentaRequestDTO("Juan Perez", 40, "hipoteca"));

        assertThat(actualizada.getNombre()).isEqualTo("Juan Perez");
        assertThat(actualizada.getEdad()).isEqualTo(40);
        assertThat(actualizada.getTipo()).isEqualTo("hipoteca");
        assertThat(actualizada.getSaldo()).isEqualTo(500.0);
        assertThat(actualizada.getClienteId()).isEqualTo(7L);
    }

    @Test
    void actualizarCuenta_cerradaLanzaExcepcion() {
        CuentaInteres cuenta = cuenta(101L, 0.0);
        cuenta.setEstado(EstadoCuenta.CERRADA);
        when(cuentaInteresRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(cuenta));

        assertThrows(CuentaCerradaException.class, () -> cuentaService.actualizarCuenta(101L,
                new ActualizarCuentaRequestDTO("Juan Perez", 40, "hipoteca")));
        verify(cuentaInteresRepository, never()).save(any());
    }

    @Test
    void debitar_cuentaCerradaLanzaExcepcionYNoCambiaSaldo() {
        CuentaInteres cuenta = cuenta(101L, 500.0);
        cuenta.setEstado(EstadoCuenta.CERRADA);
        when(cuentaInteresRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(cuenta));

        CuentaCerradaException ex = assertThrows(CuentaCerradaException.class, () -> cuentaService.debitar(101L, 100.0));
        assertThat(ex.getMessage()).isEqualTo("La cuenta 101 esta cerrada");
        assertThat(cuenta.getSaldo()).isEqualTo(500.0);
        verify(cuentaInteresRepository, never()).save(any());
    }

    @Test
    void cuentaConEstadoNullSeTrataComoActiva() {
        CuentaInteres cuenta = cuenta(101L, 500.0);
        cuenta.setEstado(null);

        assertThat(cuenta.getEstado()).isEqualTo(EstadoCuenta.ACTIVA);
        assertThat(cuenta.estaCerrada()).isFalse();
    }

    // ------------------------------------------------------------------ Idempotencia

    @Test
    void debitarConClave_registraElMovimiento() {
        CuentaInteres cuenta = cuenta(101L, 500.0);
        when(cuentaInteresRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(cuenta));
        when(movimientoIdempotenteRepository.findById("pago-7-debito")).thenReturn(Optional.empty());

        double nuevoSaldo = cuentaService.debitar(101L, 200.0, "pago-7-debito");

        assertThat(nuevoSaldo).isEqualTo(300.0);
        ArgumentCaptor<MovimientoIdempotente> captor = ArgumentCaptor.forClass(MovimientoIdempotente.class);
        verify(movimientoIdempotenteRepository).save(captor.capture());
        assertThat(captor.getValue().getClave()).isEqualTo("pago-7-debito");
        assertThat(captor.getValue().getOperacion()).isEqualTo("debito");
        assertThat(captor.getValue().getNuevoSaldo()).isEqualTo(300.0);
    }

    @Test
    void debitarRepetidoConLaMismaClave_devuelveElResultadoOriginalSinMoverSaldo() {
        CuentaInteres cuenta = cuenta(101L, 300.0);
        when(cuentaInteresRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(cuenta));
        when(movimientoIdempotenteRepository.findById("pago-7-debito"))
                .thenReturn(Optional.of(movimiento("pago-7-debito", 101L, "debito", 200.0, 300.0)));

        double nuevoSaldo = cuentaService.debitar(101L, 200.0, "pago-7-debito");

        assertThat(nuevoSaldo).isEqualTo(300.0);
        assertThat(cuenta.getSaldo()).isEqualTo(300.0);
        verify(cuentaInteresRepository, never()).save(any());
        verify(movimientoIdempotenteRepository, never()).save(any());
    }

    @Test
    void acreditarRepetidoConLaMismaClave_noAcreditaDosVeces() {
        CuentaInteres cuenta = cuenta(102L, 350.0);
        when(cuentaInteresRepository.findByIdForUpdate(102L)).thenReturn(Optional.of(cuenta));
        when(movimientoIdempotenteRepository.findById("pago-8-credito"))
                .thenReturn(Optional.of(movimiento("pago-8-credito", 102L, "credito", 250.0, 350.0)));

        assertThat(cuentaService.acreditar(102L, 250.0, "pago-8-credito")).isEqualTo(350.0);
        assertThat(cuenta.getSaldo()).isEqualTo(350.0);
        verify(cuentaInteresRepository, never()).save(any());
    }

    @Test
    void claveReutilizadaConOtroMovimiento_lanza422SinMoverSaldo() {
        CuentaInteres cuenta = cuenta(101L, 500.0);
        when(cuentaInteresRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(cuenta));
        when(movimientoIdempotenteRepository.findById("pago-7-debito"))
                .thenReturn(Optional.of(movimiento("pago-7-debito", 101L, "debito", 200.0, 300.0)));

        assertThrows(ClaveIdempotenciaReutilizadaException.class,
                () -> cuentaService.debitar(101L, 999.0, "pago-7-debito"));
        assertThat(cuenta.getSaldo()).isEqualTo(500.0);
        verify(cuentaInteresRepository, never()).save(any());
    }

    @Test
    void debitarSinClave_noUsaLaTablaDeIdempotencia() {
        when(cuentaInteresRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(cuenta(101L, 500.0)));

        cuentaService.debitar(101L, 100.0);

        verifyNoInteractions(movimientoIdempotenteRepository);
    }

    @Test
    void resolverMovimientoAplicado_informaAplicadoSinTocarNada() {
        when(movimientoIdempotenteRepository.findById("pago-7-debito"))
                .thenReturn(Optional.of(movimiento("pago-7-debito", 101L, "debito", 200.0, 300.0)));

        ResolucionMovimientoDTO resolucion = cuentaService.resolverMovimiento("pago-7-debito", 101L);

        assertThat(resolucion.aplicado()).isTrue();
        assertThat(resolucion.nuevoSaldo()).isEqualTo(300.0);
        verify(cuentaInteresRepository).findByIdForUpdate(101L);
        verify(movimientoIdempotenteRepository, never()).save(any());
    }

    @Test
    void resolverMovimientoNoAplicado_loAnulaParaSiempre() {
        when(movimientoIdempotenteRepository.findById("pago-7-credito")).thenReturn(Optional.empty());

        ResolucionMovimientoDTO resolucion = cuentaService.resolverMovimiento("pago-7-credito", 102L);

        assertThat(resolucion.aplicado()).isFalse();
        ArgumentCaptor<MovimientoIdempotente> captor = ArgumentCaptor.forClass(MovimientoIdempotente.class);
        verify(movimientoIdempotenteRepository).save(captor.capture());
        assertThat(captor.getValue().getOperacion()).isEqualTo("anulado");
        assertThat(captor.getValue().getCuentaId()).isEqualTo(102L);
    }

    @Test
    void creditoAtrasadoConClaveAnulada_seRechazaSinMoverSaldo() {
        CuentaInteres cuenta = cuenta(102L, 500.0);
        when(cuentaInteresRepository.findByIdForUpdate(102L)).thenReturn(Optional.of(cuenta));
        when(movimientoIdempotenteRepository.findById("pago-7-credito"))
                .thenReturn(Optional.of(movimiento("pago-7-credito", 102L, "anulado", 0.0, 0.0)));

        assertThrows(ClaveIdempotenciaReutilizadaException.class,
                () -> cuentaService.acreditar(102L, 50.0, "pago-7-credito"));
        assertThat(cuenta.getSaldo()).isEqualTo(500.0);
        verify(cuentaInteresRepository, never()).save(any());
    }

    private MovimientoIdempotente movimiento(String clave, Long cuentaId, String operacion, Double monto, Double nuevoSaldo) {
        MovimientoIdempotente m = new MovimientoIdempotente();
        m.setClave(clave);
        m.setCuentaId(cuentaId);
        m.setOperacion(operacion);
        m.setMonto(monto);
        m.setNuevoSaldo(nuevoSaldo);
        return m;
    }

    // El TransactionTemplate simulado ejecuta el callback directamente, sin transaccion real.
    @SuppressWarnings("unchecked")
    private void ejecutarTransaccionesDirecto() {
        when(transactionTemplate.execute(any())).thenAnswer(invocacion ->
                ((TransactionCallback<Object>) invocacion.getArgument(0)).doInTransaction(null));
    }

    private AperturaCuentaRequestDTO aperturaPara(Long clienteId) {
        return new AperturaCuentaRequestDTO(clienteId, "Ana Perez", 30, "ahorro", 1000.0);
    }

    private CuentaInteres cuenta(Long cuentaId, Double saldo) {
        CuentaInteres cuenta = new CuentaInteres();
        cuenta.setCuentaId(cuentaId);
        cuenta.setNombre("Titular");
        cuenta.setEdad(30);
        cuenta.setTipo("ahorro");
        cuenta.setSaldo(saldo);
        return cuenta;
    }
}
