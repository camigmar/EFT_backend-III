package com.banco.pagos.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "pagos")
public class Pago {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TipoPago tipo;

    // Cuenta sobre la que se hace el retiro o el deposito, o la que paga en una transferencia.
    @Column(nullable = false)
    private Long cuentaOrigen;

    // Solo en transferencias.
    private Long cuentaDestino;

    @Column(nullable = false)
    private Double monto;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoPago estado;

    @Column(length = 1000)
    private String motivo;

    @Column(nullable = false)
    private LocalDateTime fecha;

    // Idempotency-Key del cliente (opcional, unica): una solicitud repetida devuelve este mismo pago.
    @Column(unique = true, length = 100)
    private String idempotencyKey;

    // Tipo, cuentas y monto de la solicitud original: misma clave con otra huella -> 422.
    private String huellaSolicitud;

    // Status HTTP con que se respondio (201, 409, 404, 503...), para repetir la misma respuesta.
    private Integer statusRespuesta;

    // true cuando ReconciliadorPagos ya resolvio un pago de resultado incierto (no se vuelve a revisar).
    private Boolean reconciliado;

    public Long getId() {
        return id;
    }
    public void setId(Long id) {
        this.id = id;
    }
    public TipoPago getTipo() {
        return tipo;
    }
    public void setTipo(TipoPago tipo) {
        this.tipo = tipo;
    }
    public Long getCuentaOrigen() {
        return cuentaOrigen;
    }
    public void setCuentaOrigen(Long cuentaOrigen) {
        this.cuentaOrigen = cuentaOrigen;
    }
    public Long getCuentaDestino() {
        return cuentaDestino;
    }
    public void setCuentaDestino(Long cuentaDestino) {
        this.cuentaDestino = cuentaDestino;
    }
    public Double getMonto() {
        return monto;
    }
    public void setMonto(Double monto) {
        this.monto = monto;
    }
    public EstadoPago getEstado() {
        return estado;
    }
    public void setEstado(EstadoPago estado) {
        this.estado = estado;
    }
    public String getMotivo() {
        return motivo;
    }
    public void setMotivo(String motivo) {
        this.motivo = motivo;
    }
    public LocalDateTime getFecha() {
        return fecha;
    }
    public void setFecha(LocalDateTime fecha) {
        this.fecha = fecha;
    }
    public String getIdempotencyKey() {
        return idempotencyKey;
    }
    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
    public String getHuellaSolicitud() {
        return huellaSolicitud;
    }
    public void setHuellaSolicitud(String huellaSolicitud) {
        this.huellaSolicitud = huellaSolicitud;
    }
    public Integer getStatusRespuesta() {
        return statusRespuesta;
    }
    public void setStatusRespuesta(Integer statusRespuesta) {
        this.statusRespuesta = statusRespuesta;
    }
    public Boolean getReconciliado() {
        return reconciliado;
    }
    public void setReconciliado(Boolean reconciliado) {
        this.reconciliado = reconciliado;
    }
}
