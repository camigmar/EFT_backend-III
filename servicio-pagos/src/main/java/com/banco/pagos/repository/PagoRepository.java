package com.banco.pagos.repository;

import com.banco.pagos.model.EstadoPago;
import com.banco.pagos.model.Pago;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PagoRepository extends JpaRepository<Pago, Long> {
    Optional<Pago> findByIdempotencyKey(String idempotencyKey);

    // Pagos de resultado incierto, aun sin reconciliar: REQUIERE_REVISION; FALLIDO o COMPENSADO que terminaron
    // por servicio-cuentas no disponible (503: el movimiento pudo aplicarse sin respuesta); y PENDIENTE antiguos
    // (la replica que los procesaba se cayo a mitad de la saga).
    @Query("select p from Pago p where (p.reconciliado is null or p.reconciliado = false) and ("
            + "p.estado = :revision or (p.estado in :inciertos and p.statusRespuesta = 503) "
            + "or (p.estado = :pendiente and p.fecha < :pendienteAntesDe)) order by p.id")
    List<Pago> buscarPorReconciliar(@Param("revision") EstadoPago revision,
                                   @Param("inciertos") Collection<EstadoPago> inciertos,
                                   @Param("pendiente") EstadoPago pendiente,
                                   @Param("pendienteAntesDe") LocalDateTime pendienteAntesDe);

    List<Pago> findByCuentaOrigenOrCuentaDestinoOrderByFechaDesc(Long cuentaOrigen, Long cuentaDestino);
}
