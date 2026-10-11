package com.banco.pagos.client;

import com.banco.pagos.dto.ErrorDTO;
import com.banco.pagos.dto.MovimientoCuentaRequestDTO;
import com.banco.pagos.dto.MovimientoCuentaResponseDTO;
import com.banco.pagos.dto.ResolucionMovimientoDTO;
import com.banco.pagos.dto.ResolucionMovimientoRequestDTO;
import com.banco.pagos.exception.CuentaNoEncontradaException;
import com.banco.pagos.exception.CuentasException;
import com.banco.pagos.exception.CuentasNoDisponibleException;
import com.banco.pagos.exception.CuentasRespuestaInesperadaException;
import com.banco.pagos.exception.MontoInvalidoException;
import com.banco.pagos.exception.OperacionRechazadaException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.function.Supplier;

@Component
public class CuentasClient {

    static final String INSTANCIA = "cuentas";

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public CuentasClient(RestClient cuentasRestClient, CircuitBreakerRegistry circuitBreakerRegistry,
                         RetryRegistry retryRegistry) {
        this.restClient = cuentasRestClient;
        // Configuracion de ambas instancias en config-repo (resilience4j.*.instances.cuentas).
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCIA);
        this.retry = retryRegistry.retry(INSTANCIA);
    }

    // claveIdempotencia identifica el movimiento ("pago-<id>-debito", ...): servicio-cuentas no lo aplica
    // dos veces aunque llegue repetido, por eso reintentar un debito o un credito es seguro.
    public MovimientoCuentaResponseDTO debitar(Long cuentaId, Double monto, String claveIdempotencia) {
        return ejecutar(() -> mover("debito", cuentaId, monto, claveIdempotencia));
    }

    public MovimientoCuentaResponseDTO acreditar(Long cuentaId, Double monto, String claveIdempotencia) {
        return ejecutar(() -> mover("credito", cuentaId, monto, claveIdempotencia));
    }

    // Para el reconciliador: true si el movimiento con esta clave se aplico; false si no (y desde ahora queda anulado
    // en cuentas, asi que la respuesta es definitiva). Lanza CuentasNoDisponibleException si cuentas no responde.
    public boolean movimientoAplicado(String claveIdempotencia, Long cuentaId) {
        return ejecutar(() -> resolver(claveIdempotencia, cuentaId)).aplicado();
    }

    private ResolucionMovimientoDTO resolver(String claveIdempotencia, Long cuentaId) {
        try {
            ResolucionMovimientoDTO resolucion = restClient.post()
                    .uri("/api/core/movimientos/{clave}/resolucion", claveIdempotencia)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ResolucionMovimientoRequestDTO(cuentaId))
                    .retrieve()
                    .body(ResolucionMovimientoDTO.class);
            if (resolucion == null) {
                throw new CuentasNoDisponibleException("servicio-cuentas respondio sin resolucion para " + claveIdempotencia, null);
            }
            return resolucion;
        } catch (RestClientResponseException ex) {
            throw traducir(ex, "resolucion", cuentaId);
        } catch (CuentasException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new CuentasNoDisponibleException("servicio-cuentas no esta disponible", ex);
        }
    }

    // Retry por fuera del CircuitBreaker: cada intento cuenta para el circuito. Solo se reintenta
    // CuentasNoDisponibleException (red, timeout, 5xx); nunca los 4xx. Con el circuito abierto el
    // fallback responde "no disponible" de inmediato, sin llamar ni reintentar.
    <T> T ejecutar(Supplier<T> llamada) {
        Supplier<T> protegida = Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, llamada));
        try {
            return protegida.get();
        } catch (CallNotPermittedException ex) {
            throw new CuentasNoDisponibleException("servicio-cuentas no esta disponible (circuito abierto)", ex);
        }
    }

    private MovimientoCuentaResponseDTO mover(String operacion, Long cuentaId, Double monto, String claveIdempotencia) {
        try {
            return restClient.post()
                    .uri("/api/core/cuentas/{cuentaId}/{operacion}", cuentaId, operacion)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        if (claveIdempotencia != null) {
                            h.set("Idempotency-Key", claveIdempotencia);
                        }
                    })
                    .body(new MovimientoCuentaRequestDTO(monto))
                    .retrieve()
                    .body(MovimientoCuentaResponseDTO.class);
        } catch (RestClientResponseException ex) {
            throw traducir(ex, operacion, cuentaId);
        } catch (RuntimeException ex) {
            // Conexion rechazada, timeout, sin instancias en Eureka o sin token de auth-server.
            throw new CuentasNoDisponibleException("servicio-cuentas no esta disponible", ex);
        }
    }

    private CuentasException traducir(RestClientResponseException ex, String operacion, Long cuentaId) {
        int status = ex.getStatusCode().value();
        return switch (status) {
            // 409 cubre saldo insuficiente y cuenta cerrada: se usa el mensaje de cuentas para no confundirlos.
            case 409 -> new OperacionRechazadaException(
                    mensajeDeCuentas(ex, "servicio-cuentas rechazo el " + operacion + " en la cuenta " + cuentaId));
            case 404 -> new CuentaNoEncontradaException("La cuenta " + cuentaId + " no existe");
            case 400 -> new MontoInvalidoException("servicio-cuentas rechazo el monto del " + operacion);
            default -> ex.getStatusCode().is4xxClientError()
                    // Otro 4xx (401/403 tras renovar el token, 422 de clave reutilizada): no se reintenta.
                    ? new CuentasRespuestaInesperadaException(
                            mensajeDeCuentas(ex, "servicio-cuentas respondio " + status + " al " + operacion), ex)
                    : new CuentasNoDisponibleException("servicio-cuentas respondio " + status + " al " + operacion, ex);
        };
    }

    private String mensajeDeCuentas(RestClientResponseException ex, String porDefecto) {
        try {
            ErrorDTO error = ex.getResponseBodyAs(ErrorDTO.class);
            if (error != null && error.mensaje() != null && !error.mensaje().isBlank()) {
                return error.mensaje();
            }
        } catch (RuntimeException ignorada) {
            // Cuerpo vacio o que no es un ErrorDTO: se usa el mensaje por defecto.
        }
        return porDefecto;
    }
}
