package com.banco.core.service;

import com.banco.core.dto.EjecucionBatchDTO;
import com.banco.core.dto.EstadoBatchDTO;
import com.banco.core.exception.EjecucionBatchDuplicadaException;
import com.banco.core.exception.InstanciaBatchNoEncontradaException;
import com.banco.core.exception.JobNoEncontradoException;
import com.banco.core.model.ReanudacionBatch;
import com.banco.core.repository.ReanudacionBatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BatchServiceTest {

    private static final LocalDate FECHA = LocalDate.of(2026, 10, 11);
    private static final int MAX_INTENTOS = 2;

    private JobOperator jobOperator;
    private JobRepository jobRepository;
    private TaskScheduler scheduler;
    private ReanudacionBatchRepository reanudacionRepository;
    private Job transaccionesJob;
    private JobInstance instancia;
    private BatchService batchService;

    // Ejecuciones simuladas del JobRepository, por id; la "ultima" es la de mayor id.
    private final Map<Long, JobExecution> ejecuciones = new HashMap<>();

    @BeforeEach
    void setUp() {
        jobOperator = mock(JobOperator.class);
        jobRepository = mock(JobRepository.class);
        scheduler = mock(TaskScheduler.class);
        transaccionesJob = mock(Job.class);
        instancia = mock(JobInstance.class);
        when(transaccionesJob.getName()).thenReturn("transaccionesJob");
        when(jobRepository.getJobExecution(anyLong())).thenAnswer(i -> ejecuciones.get((Long) i.getArgument(0)));
        when(jobRepository.getLastJobExecution(instancia)).thenAnswer(i ->
                ejecuciones.keySet().stream().max(Long::compare).map(ejecuciones::get).orElse(null));
        when(jobRepository.getJobExecutions(instancia)).thenAnswer(i -> List.copyOf(ejecuciones.values()));
        reanudacionRepository = mock(ReanudacionBatchRepository.class);
        batchService = new BatchService(jobOperator, jobRepository, Map.of("transaccionesJob", transaccionesJob),
                reanudacionRepository, scheduler, MAX_INTENTOS, Duration.ofSeconds(5));
    }

    private JobExecution ejecucion(long id, BatchStatus estado) {
        JobExecution ejecucion = mock(JobExecution.class);
        when(ejecucion.getId()).thenReturn(id);
        when(ejecucion.getStatus()).thenReturn(estado);
        when(ejecucion.getExitStatus()).thenReturn(estado == BatchStatus.COMPLETED ? ExitStatus.COMPLETED : ExitStatus.FAILED);
        when(ejecucion.getJobInstance()).thenReturn(instancia);
        ejecuciones.put(id, ejecucion);
        return ejecucion;
    }

    // Cada run() del job crea la siguiente ejecucion de la instancia (1, 2, 3...) con el estado indicado, en orden:
    // la primera es la ejecucion original y las demas, las reanudaciones (run con los mismos parametros).
    private void ejecucionesSucesivas(BatchStatus... estados) throws Exception {
        int[] indice = {0};
        when(jobOperator.run(eq(transaccionesJob), any(JobParameters.class))).thenAnswer(i -> {
            int n = indice[0]++;
            return ejecucion(n + 1, estados[n]);
        });
    }

    private void instanciaExistente() {
        when(jobRepository.getJobInstance(eq("transaccionesJob"), any(JobParameters.class))).thenReturn(instancia);
    }

    // Ejecuta el reintento que el servicio dejo programado en el scheduler (el ultimo).
    private void correrReintentoProgramado(int vecesProgramadoHastaAhora) {
        ArgumentCaptor<Runnable> tarea = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, times(vecesProgramadoHastaAhora)).schedule(tarea.capture(), any(Instant.class));
        tarea.getAllValues().get(vecesProgramadoHastaAhora - 1).run();
    }

    // ------------------------------------------------------------------ Ejecucion

    @Test
    void ejecutaElJobConLaFechaComoParametroIdentificadorYSinReintentosSiCompleta() throws Exception {
        when(jobOperator.run(eq(transaccionesJob), any(JobParameters.class))).thenAnswer(i -> {
            assertThat(((JobParameters) i.getArgument(1)).getString("fecha")).isEqualTo("2026-10-11");
            return ejecucion(1, BatchStatus.COMPLETED);
        });

        EjecucionBatchDTO resultado = batchService.ejecutar("transaccionesJob", FECHA);

        assertThat(resultado.estado()).isEqualTo("COMPLETED");
        assertThat(resultado.reintentosAutomaticos()).isZero();
        verifyNoInteractions(scheduler);
    }

    @Test
    void jobFallido_programaReintentosAutomaticos() throws Exception {
        when(jobOperator.run(eq(transaccionesJob), any())).thenAnswer(i -> ejecucion(1, BatchStatus.FAILED));

        EjecucionBatchDTO resultado = batchService.ejecutar("transaccionesJob", FECHA);

        assertThat(resultado.estado()).isEqualTo("FAILED");
        assertThat(resultado.reintentosAutomaticos()).isEqualTo(MAX_INTENTOS);
        verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void reintentaHastaCompletarYSeDetiene() throws Exception {
        ejecucionesSucesivas(BatchStatus.FAILED, BatchStatus.FAILED, BatchStatus.COMPLETED);
        batchService.ejecutar("transaccionesJob", FECHA);

        correrReintentoProgramado(1);   // intento 1: reanuda -> ejecucion 2 FAILED, programa el intento 2
        correrReintentoProgramado(2);   // intento 2: reanuda -> ejecucion 3 COMPLETED, no programa mas

        verify(jobOperator, times(3)).run(eq(transaccionesJob), any(JobParameters.class));
        verify(scheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
        assertThat(ejecuciones.get(3L).getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    void agotaLosIntentosYQuedaFailedSinProgramarMas() throws Exception {
        ejecucionesSucesivas(BatchStatus.FAILED, BatchStatus.FAILED, BatchStatus.FAILED, BatchStatus.FAILED);
        batchService.ejecutar("transaccionesJob", FECHA);

        correrReintentoProgramado(1);
        correrReintentoProgramado(2);

        // La ejecucion original mas MAX_INTENTOS reanudaciones, y ningun reintento mas programado.
        verify(jobOperator, times(1 + MAX_INTENTOS)).run(eq(transaccionesJob), any(JobParameters.class));
        verify(scheduler, times(MAX_INTENTOS)).schedule(any(Runnable.class), any(Instant.class));
        assertThat(ejecuciones.get(3L).getStatus()).isEqualTo(BatchStatus.FAILED);
    }

    @Test
    void reintentoAutomatico_seCancelaSiOtraReplicaYaReanudo() throws Exception {
        ejecucionesSucesivas(BatchStatus.FAILED);
        batchService.ejecutar("transaccionesJob", FECHA);
        ejecucion(2, BatchStatus.STARTED);   // la reanudo otra replica (o un /reanudar manual)

        correrReintentoProgramado(1);

        verify(jobOperator, times(1)).run(eq(transaccionesJob), any(JobParameters.class));
    }

    @Test
    void reintentoAutomatico_choqueAlReanudarNoRompeNada() throws Exception {
        when(jobOperator.run(eq(transaccionesJob), any()))
                .thenAnswer(i -> ejecucion(1, BatchStatus.FAILED))
                .thenThrow(new JobExecutionAlreadyRunningException("ya corre en otra replica"));
        batchService.ejecutar("transaccionesJob", FECHA);

        correrReintentoProgramado(1);

        verify(scheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void jobYaCompletadoParaEsaFecha_lanzaDuplicadaSinReintentar() throws Exception {
        when(jobOperator.run(eq(transaccionesJob), any())).thenThrow(new JobInstanceAlreadyCompleteException("completo"));

        EjecucionBatchDuplicadaException ex = assertThrows(EjecucionBatchDuplicadaException.class,
                () -> batchService.ejecutar("transaccionesJob", FECHA));
        assertThat(ex.getMessage()).contains("ya se ejecuto");
        verifyNoInteractions(scheduler);
    }

    @Test
    void jobEnEjecucionEnOtraReplica_lanzaDuplicada() throws Exception {
        when(jobOperator.run(eq(transaccionesJob), any())).thenThrow(new JobExecutionAlreadyRunningException("corriendo"));

        assertThrows(EjecucionBatchDuplicadaException.class, () -> batchService.ejecutar("transaccionesJob", FECHA));
    }

    @Test
    void carreraEntreReplicas_cualquierFormaDeFallo_lanzaDuplicadaSiLaInstanciaYaExiste() throws Exception {
        instanciaExistente();
        when(jobOperator.run(eq(transaccionesJob), any()))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry for key JOB_INST_UN"))
                .thenThrow(new DeadlockLoserDataAccessException("Deadlock found", null))
                .thenThrow(new IllegalStateException(
                        "Cannot find any job execution for job instance: JobInstance: id=1, version=0, Job=[transaccionesJob]"));

        for (int i = 0; i < 3; i++) {
            EjecucionBatchDuplicadaException ex = assertThrows(EjecucionBatchDuplicadaException.class,
                    () -> batchService.ejecutar("transaccionesJob", FECHA));
            assertThat(ex.getMessage()).contains("otra instancia");
        }
    }

    @Test
    void errorSinInstanciaCreada_noSeConfundeConUnaDuplicada() throws Exception {
        when(jobRepository.getJobInstance(eq("transaccionesJob"), any(JobParameters.class))).thenReturn(null);
        when(jobOperator.run(eq(transaccionesJob), any())).thenThrow(new IllegalStateException("otro error"));

        assertThrows(IllegalStateException.class, () -> batchService.ejecutar("transaccionesJob", FECHA));
    }

    @Test
    void jobInexistente_lanzaNoEncontradoSinEjecutar() {
        assertThrows(JobNoEncontradoException.class, () -> batchService.ejecutar("otroJob", FECHA));
        verifyNoInteractions(jobOperator);
    }

    // ------------------------------------------------------------------ Reanudacion manual y consulta

    @Test
    void reanudar_sinEjecucionesParaEsaFecha_lanzaNoEncontrada() {
        when(jobRepository.getJobInstance(eq("transaccionesJob"), any(JobParameters.class))).thenReturn(null);

        assertThrows(InstanciaBatchNoEncontradaException.class, () -> batchService.reanudar("transaccionesJob", FECHA));
    }

    @Test
    void reanudar_jobCompletado_lanzaDuplicadaSinReiniciar() throws Exception {
        instanciaExistente();
        ejecucion(1, BatchStatus.COMPLETED);

        EjecucionBatchDuplicadaException ex = assertThrows(EjecucionBatchDuplicadaException.class,
                () -> batchService.reanudar("transaccionesJob", FECHA));
        assertThat(ex.getMessage()).contains("ya se completo");
        verify(jobOperator, never()).run(any(), any());
    }

    @Test
    void reanudar_jobEnEjecucion_lanzaDuplicada() throws Exception {
        instanciaExistente();
        ejecucion(1, BatchStatus.STARTED);

        assertThrows(EjecucionBatchDuplicadaException.class, () -> batchService.reanudar("transaccionesJob", FECHA));
        verify(jobOperator, never()).run(any(), any());
    }

    @Test
    void reanudar_jobFallido_loReiniciaUnaVez() throws Exception {
        instanciaExistente();
        ejecucion(1, BatchStatus.FAILED);
        when(jobOperator.run(eq(transaccionesJob), any(JobParameters.class))).thenAnswer(i -> {
            assertThat(((JobParameters) i.getArgument(1)).getString("fecha")).isEqualTo("2026-10-11");
            return ejecucion(2, BatchStatus.COMPLETED);
        });

        EjecucionBatchDTO resultado = batchService.reanudar("transaccionesJob", FECHA);

        assertThat(resultado.ejecucionId()).isEqualTo(2L);
        assertThat(resultado.estado()).isEqualTo("COMPLETED");
        verify(jobOperator, times(1)).run(eq(transaccionesJob), any(JobParameters.class));
        verifyNoInteractions(scheduler);
    }

    @Test
    void reanudar_choqueConOtraReplicaQueYaEsta_lanzaDuplicada() throws Exception {
        instanciaExistente();
        ejecucion(1, BatchStatus.FAILED);
        when(jobOperator.run(eq(transaccionesJob), any())).thenThrow(new JobExecutionAlreadyRunningException("ya corre"));

        assertThrows(EjecucionBatchDuplicadaException.class, () -> batchService.reanudar("transaccionesJob", FECHA));
    }

    @Test
    void reanudar_choqueConOtraReplica_lanzaDuplicada() throws Exception {
        instanciaExistente();
        ejecucion(1, BatchStatus.FAILED);
        when(jobOperator.run(eq(transaccionesJob), any())).thenAnswer(i -> {
            ejecucion(2, BatchStatus.STARTED);   // la otra replica creo su ejecucion primero
            throw new IllegalStateException("conflicto al crear la ejecucion");
        });

        EjecucionBatchDuplicadaException ex = assertThrows(EjecucionBatchDuplicadaException.class,
                () -> batchService.reanudar("transaccionesJob", FECHA));
        assertThat(ex.getMessage()).contains("otra instancia");
    }

    @Test
    void reanudar_otraReplicaYaReclamoEsaEjecucion_responde409SinEjecutar() throws Exception {
        instanciaExistente();
        ejecucion(1, BatchStatus.FAILED);
        when(reanudacionRepository.saveAndFlush(any(ReanudacionBatch.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry '1' for key 'PRIMARY'"));

        EjecucionBatchDuplicadaException ex = assertThrows(EjecucionBatchDuplicadaException.class,
                () -> batchService.reanudar("transaccionesJob", FECHA));

        assertThat(ex.getMessage()).contains("otra instancia");
        verify(jobOperator, never()).run(any(), any());
    }

    @Test
    void reanudar_reclamaLaEjecucionFallidaAntesDeReiniciar() throws Exception {
        instanciaExistente();
        ejecucion(1, BatchStatus.FAILED);
        when(jobOperator.run(eq(transaccionesJob), any(JobParameters.class))).thenAnswer(i -> ejecucion(2, BatchStatus.COMPLETED));

        batchService.reanudar("transaccionesJob", FECHA);

        ArgumentCaptor<ReanudacionBatch> reclamo = ArgumentCaptor.forClass(ReanudacionBatch.class);
        verify(reanudacionRepository).saveAndFlush(reclamo.capture());
        assertThat(reclamo.getValue().getId()).isEqualTo(1L);
        verify(reanudacionRepository, never()).deleteById(any());
    }

    @Test
    void reanudar_errorSinEjecucionNueva_liberaElReclamo() throws Exception {
        instanciaExistente();
        ejecucion(1, BatchStatus.FAILED);
        when(jobOperator.run(eq(transaccionesJob), any())).thenThrow(new IllegalStateException("base no disponible"));

        assertThrows(IllegalStateException.class, () -> batchService.reanudar("transaccionesJob", FECHA));

        verify(reanudacionRepository).deleteById(1L);
    }

    @Test
    void consultar_devuelveEstadoIntentosYEjecucionesEnOrden() {
        instanciaExistente();
        ejecucion(2, BatchStatus.COMPLETED);
        ejecucion(1, BatchStatus.FAILED);

        EstadoBatchDTO estado = batchService.consultar("transaccionesJob", FECHA);

        assertThat(estado.estado()).isEqualTo("COMPLETED");
        assertThat(estado.intentos()).isEqualTo(2);
        assertThat(estado.ejecuciones()).extracting(EstadoBatchDTO.Ejecucion::id).containsExactly(1L, 2L);
        assertThat(estado.ejecuciones().get(0).estado()).isEqualTo("FAILED");
    }
}
