package com.banco.core.service;

import com.banco.core.dto.EjecucionBatchDTO;
import com.banco.core.exception.EjecucionBatchDuplicadaException;
import com.banco.core.exception.JobNoEncontradoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BatchServiceTest {

    private static final LocalDate FECHA = LocalDate.of(2026, 10, 11);

    private JobOperator jobOperator;
    private JobRepository jobRepository;
    private Job interesesJob;
    private BatchService batchService;

    @BeforeEach
    void setUp() {
        jobOperator = mock(JobOperator.class);
        jobRepository = mock(JobRepository.class);
        interesesJob = mock(Job.class);
        when(interesesJob.getName()).thenReturn("interesesJob");
        batchService = new BatchService(jobOperator, jobRepository, Map.of("interesesJob", interesesJob));
    }

    // Simula que la otra replica ya creo la JobInstance de este job y fecha.
    private void instanciaYaCreadaPorOtraReplica() {
        when(jobRepository.getJobInstance(eq("interesesJob"), any(JobParameters.class))).thenReturn(mock(JobInstance.class));
    }

    @Test
    void ejecutaElJobConLaFechaComoParametroIdentificador() throws Exception {
        JobExecution ejecucion = mock(JobExecution.class);
        when(ejecucion.getId()).thenReturn(5L);
        when(ejecucion.getStatus()).thenReturn(BatchStatus.COMPLETED);
        when(ejecucion.getExitStatus()).thenReturn(ExitStatus.COMPLETED);
        when(jobOperator.run(eq(interesesJob), any(JobParameters.class))).thenAnswer(invocacion -> {
            JobParameters parametros = invocacion.getArgument(1);
            assertThat(parametros.getString("fecha")).isEqualTo("2026-10-11");
            return ejecucion;
        });

        EjecucionBatchDTO resultado = batchService.ejecutar("interesesJob", FECHA);

        assertThat(resultado.estado()).isEqualTo("COMPLETED");
        assertThat(resultado.ejecucionId()).isEqualTo(5L);
    }

    @Test
    void jobYaCompletadoParaEsaFecha_lanzaDuplicada() throws Exception {
        when(jobOperator.run(eq(interesesJob), any())).thenThrow(new JobInstanceAlreadyCompleteException("completo"));

        EjecucionBatchDuplicadaException ex = assertThrows(EjecucionBatchDuplicadaException.class,
                () -> batchService.ejecutar("interesesJob", FECHA));
        assertThat(ex.getMessage()).contains("ya se ejecuto");
    }

    @Test
    void jobEnEjecucionEnOtraReplica_lanzaDuplicada() throws Exception {
        when(jobOperator.run(eq(interesesJob), any())).thenThrow(new JobExecutionAlreadyRunningException("corriendo"));

        assertThrows(EjecucionBatchDuplicadaException.class, () -> batchService.ejecutar("interesesJob", FECHA));
    }

    @Test
    void carreraEntreReplicas_cualquierFormaDeFallo_lanzaDuplicadaSiLaInstanciaYaExiste() throws Exception {
        instanciaYaCreadaPorOtraReplica();
        when(jobOperator.run(eq(interesesJob), any()))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry for key JOB_INST_UN"))
                .thenThrow(new DeadlockLoserDataAccessException("Deadlock found", null))
                .thenThrow(new IllegalStateException(
                        "Cannot find any job execution for job instance: JobInstance: id=1, version=0, Job=[interesesJob]"));

        for (int i = 0; i < 3; i++) {
            EjecucionBatchDuplicadaException ex = assertThrows(EjecucionBatchDuplicadaException.class,
                    () -> batchService.ejecutar("interesesJob", FECHA));
            assertThat(ex.getMessage()).contains("otra instancia");
        }
    }

    @Test
    void errorSinInstanciaCreada_noSeConfundeConUnaDuplicada() throws Exception {
        when(jobRepository.getJobInstance(eq("interesesJob"), any(JobParameters.class))).thenReturn(null);
        when(jobOperator.run(eq(interesesJob), any())).thenThrow(new IllegalStateException("otro error"));

        assertThrows(IllegalStateException.class, () -> batchService.ejecutar("interesesJob", FECHA));
    }

    @Test
    void jobInexistente_lanzaNoEncontradoSinEjecutar() {
        assertThrows(JobNoEncontradoException.class, () -> batchService.ejecutar("otroJob", FECHA));
        verifyNoInteractions(jobOperator);
    }
}
