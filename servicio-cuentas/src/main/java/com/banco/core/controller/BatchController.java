package com.banco.core.controller;

import com.banco.core.dto.EjecucionBatchDTO;
import com.banco.core.dto.ErrorDTO;
import com.banco.core.exception.EjecucionBatchDuplicadaException;
import com.banco.core.exception.JobNoEncontradoException;
import com.banco.core.service.BatchService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

// Disparo manual de los jobs (interesesJob, transaccionesJob, cuentasAnualesJob), con token de scope "interno".
// Sincrono: responde cuando el job termina. Una sola ejecucion por job y fecha entre todas las replicas.
@RestController
@RequestMapping("/api/core/batch")
public class BatchController {

    private final BatchService batchService;

    public BatchController(BatchService batchService) {
        this.batchService = batchService;
    }

    @PostMapping("/{job}")
    public EjecucionBatchDTO ejecutar(@PathVariable String job,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {
        return batchService.ejecutar(job, fecha != null ? fecha : LocalDate.now());
    }

    @ExceptionHandler(EjecucionBatchDuplicadaException.class)
    public ResponseEntity<ErrorDTO> handleDuplicada(EjecucionBatchDuplicadaException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorDTO(ex.getMessage()));
    }

    @ExceptionHandler(JobNoEncontradoException.class)
    public ResponseEntity<ErrorDTO> handleNoEncontrado(JobNoEncontradoException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorDTO(ex.getMessage()));
    }
}
