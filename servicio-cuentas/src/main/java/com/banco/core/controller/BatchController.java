package com.banco.core.controller;

import com.banco.core.dto.EjecucionBatchDTO;
import com.banco.core.dto.ErrorDTO;
import com.banco.core.dto.EstadoBatchDTO;
import com.banco.core.exception.EjecucionBatchDuplicadaException;
import com.banco.core.exception.InstanciaBatchNoEncontradaException;
import com.banco.core.exception.JobNoEncontradoException;
import com.banco.core.service.BatchService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

// Disparo, consulta y reanudacion de los jobs (interesesJob, transaccionesJob, cuentasAnualesJob), con token de
// scope "interno". Una sola ejecucion por job y fecha entre todas las replicas. Sin fecha se usa la de hoy.
@RestController
@RequestMapping("/api/core/batch")
public class BatchController {

    private final BatchService batchService;

    public BatchController(BatchService batchService) {
        this.batchService = batchService;
    }

    // Sincrono: responde cuando termina la primera ejecucion. Si termina FAILED, las reanudaciones automaticas
    // quedan programadas en segundo plano (reintentosAutomaticos) y su avance se ve con el GET.
    @PostMapping("/{job}")
    public EjecucionBatchDTO ejecutar(@PathVariable String job,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {
        return batchService.ejecutar(job, fechaOHoy(fecha));
    }

    // Reanudacion manual de la ultima ejecucion FAILED (por ejemplo, tras agotar los reintentos automaticos).
    @PostMapping("/{job}/reanudar")
    public EjecucionBatchDTO reanudar(@PathVariable String job,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {
        return batchService.reanudar(job, fechaOHoy(fecha));
    }

    @GetMapping("/{job}")
    public EstadoBatchDTO consultar(@PathVariable String job,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {
        return batchService.consultar(job, fechaOHoy(fecha));
    }

    private LocalDate fechaOHoy(LocalDate fecha) {
        return fecha != null ? fecha : LocalDate.now();
    }

    @ExceptionHandler(EjecucionBatchDuplicadaException.class)
    public ResponseEntity<ErrorDTO> handleDuplicada(EjecucionBatchDuplicadaException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorDTO(ex.getMessage()));
    }

    @ExceptionHandler({JobNoEncontradoException.class, InstanciaBatchNoEncontradaException.class})
    public ResponseEntity<ErrorDTO> handleNoEncontrado(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorDTO(ex.getMessage()));
    }
}
