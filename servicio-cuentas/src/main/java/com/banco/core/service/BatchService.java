package com.banco.core.service;

import com.banco.core.dto.EjecucionBatchDTO;
import com.banco.core.dto.EstadoBatchDTO;
import com.banco.core.exception.EjecucionBatchDuplicadaException;
import com.banco.core.exception.InstanciaBatchNoEncontradaException;
import com.banco.core.exception.JobNoEncontradoException;
import com.banco.core.model.ReanudacionBatch;
import com.banco.core.repository.ReanudacionBatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

// Los jobs no se ejecutan al arrancar (spring.batch.job.enabled=false): se disparan por endpoint.
// El parametro identificador "fecha" define la JobInstance; el JobRepository JDBC (compartido por las
// replicas) no deja ejecutar dos veces la misma instancia, ni en paralelo ni despues de completada.
//
// Reejecucion ante fallos criticos: si una ejecucion termina FAILED (decider CRITICO o excepcion), se reanuda
// sola (restart de esa JobInstance desde la ejecucion fallida), hasta batch.reejecucion.max-intentos veces y esperando
// batch.reejecucion.espera entre intentos. La primera ejecucion sigue siendo sincrona (la peticion responde con
// su resultado); las reanudaciones corren en segundo plano, para no retener el hilo HTTP durante las esperas, y
// su avance se consulta con consultar(). Solo agenda reintentos la replica que ejecuto el job; antes de cada
// uno se verifica que la ejecucion fallida siga siendo la ultima, asi no se reanuda dos veces lo mismo.
@Service
public class BatchService {

    private static final Logger logger = LoggerFactory.getLogger(BatchService.class);

    private final JobOperator jobOperator;
    private final JobRepository jobRepository;
    private final Map<String, Job> jobs;
    private final ReanudacionBatchRepository reanudacionRepository;
    private final TaskScheduler scheduler;
    private final int maxIntentos;
    private final Duration espera;

    public BatchService(JobOperator jobOperator, JobRepository jobRepository, Map<String, Job> jobs,
                        ReanudacionBatchRepository reanudacionRepository,
                        @Qualifier("batchReejecucionScheduler") TaskScheduler scheduler,
                        @Value("${batch.reejecucion.max-intentos:2}") int maxIntentos,
                        @Value("${batch.reejecucion.espera:5s}") Duration espera) {
        this.jobOperator = jobOperator;
        this.jobRepository = jobRepository;
        this.jobs = jobs;
        this.reanudacionRepository = reanudacionRepository;
        this.scheduler = scheduler;
        this.maxIntentos = maxIntentos;
        this.espera = espera;
    }

    public EjecucionBatchDTO ejecutar(String nombreJob, LocalDate fecha) {
        Job job = buscarJob(nombreJob);
        JobParameters parametros = parametros(fecha);
        JobExecution ejecucion;
        try {
            // run() y no start(): en Spring Batch 6, start() ignora los parametros cuando el job define un
            // incrementer (RunIdIncrementer) y lanza "la siguiente instancia" con run.id, asi que la fecha no
            // identificaria la instancia y dos replicas calcularian el mismo run.id a la vez. run() usa la
            // JobInstance de exactamente estos parametros.
            ejecucion = jobOperator.run(job, parametros);
        } catch (JobInstanceAlreadyCompleteException ex) {
            throw new EjecucionBatchDuplicadaException("El job " + nombreJob + " ya se ejecuto para la fecha " + fecha);
        } catch (JobExecutionAlreadyRunningException ex) {
            throw new EjecucionBatchDuplicadaException("El job " + nombreJob + " ya esta en ejecucion para la fecha " + fecha);
        } catch (Exception ex) {
            // Carrera entre replicas: Spring Batch 6 crea la JobInstance y la JobExecution en transacciones separadas,
            // asi que la replica que pierde falla de varias formas segun el momento (clave duplicada JOB_INST_UN,
            // bloqueo, "Cannot find any job execution for job instance"...). Si la JobInstance de este job y fecha
            // ya existe en el repositorio compartido, la creo la otra replica, que es la que ejecuta el job.
            if (instanciaCreadaPorOtraReplica(job, parametros)) {
                logger.info("Job {} (fecha {}) ya lo inicio otra replica: {}: {}", nombreJob, fecha,
                        ex.getClass().getSimpleName(), ex.getMessage());
                throw new EjecucionBatchDuplicadaException(mensajeOtraInstancia(nombreJob, fecha));
            }
            throw new IllegalStateException("No se pudo ejecutar el job " + nombreJob + ": " + ex.getMessage(), ex);
        }
        logger.info("Job {} (fecha {}) termino con estado {}", nombreJob, fecha, ejecucion.getStatus());

        int reintentos = 0;
        if (ejecucion.getStatus() == BatchStatus.FAILED && maxIntentos > 0) {
            programarReintento(nombreJob, fecha, ejecucion.getId(), 1);
            reintentos = maxIntentos;
        }
        return dto(nombreJob, fecha, ejecucion, reintentos);
    }

    // Reanudacion manual (una vez) de la ultima ejecucion FAILED o STOPPED de la instancia.
    public EjecucionBatchDTO reanudar(String nombreJob, LocalDate fecha) {
        JobInstance instancia = buscarInstancia(nombreJob, fecha);
        JobExecution ultima = jobRepository.getLastJobExecution(instancia);
        if (ultima == null) {
            throw new InstanciaBatchNoEncontradaException("El job " + nombreJob + " no tiene ejecuciones para la fecha " + fecha);
        }
        if (ultima.getStatus() == BatchStatus.COMPLETED) {
            throw new EjecucionBatchDuplicadaException("El job " + nombreJob + " ya se completo para la fecha " + fecha);
        }
        if (ultima.getStatus().isRunning()) {
            throw new EjecucionBatchDuplicadaException("El job " + nombreJob + " ya esta en ejecucion para la fecha " + fecha);
        }
        JobExecution nueva = reiniciar(nombreJob, fecha, ultima);
        logger.info("Job {} (fecha {}) reanudado manualmente: ejecucion {} termino con estado {}",
                nombreJob, fecha, nueva.getId(), nueva.getStatus());
        return dto(nombreJob, fecha, nueva, 0);
    }

    public EstadoBatchDTO consultar(String nombreJob, LocalDate fecha) {
        JobInstance instancia = buscarInstancia(nombreJob, fecha);
        List<EstadoBatchDTO.Ejecucion> ejecuciones = jobRepository.getJobExecutions(instancia).stream()
                .sorted(Comparator.comparing(JobExecution::getId))
                .map(e -> new EstadoBatchDTO.Ejecucion(e.getId(), e.getStatus().name(),
                        e.getExitStatus().getExitCode(), e.getStartTime(), e.getEndTime()))
                .toList();
        String estado = ejecuciones.isEmpty() ? "SIN_EJECUCIONES" : ejecuciones.get(ejecuciones.size() - 1).estado();
        return new EstadoBatchDTO(nombreJob, fecha.toString(), estado, ejecuciones.size(), ejecuciones);
    }

    private void programarReintento(String nombreJob, LocalDate fecha, long ejecucionFallidaId, int intento) {
        logger.warn("Job {} (fecha {}) termino FAILED (ejecucion {}): reintento automatico {} de {} en {}",
                nombreJob, fecha, ejecucionFallidaId, intento, maxIntentos, espera);
        scheduler.schedule(() -> reintentar(nombreJob, fecha, ejecucionFallidaId, intento), Instant.now().plus(espera));
    }

    void reintentar(String nombreJob, LocalDate fecha, long ejecucionFallidaId, int intento) {
        try {
            JobExecution fallida = jobRepository.getJobExecution(ejecucionFallidaId);
            JobExecution ultima = jobRepository.getLastJobExecution(fallida.getJobInstance());
            if (ultima == null || ultima.getId() != ejecucionFallidaId) {
                // Otra replica o una reanudacion manual ya la retomo: no se reanuda dos veces.
                logger.info("Job {} (fecha {}): la ejecucion {} ya fue reanudada (ultima: {}); se cancela el reintento automatico",
                        nombreJob, fecha, ejecucionFallidaId, ultima != null ? ultima.getId() : null);
                return;
            }
            JobExecution nueva = reiniciar(nombreJob, fecha, fallida);
            if (nueva.getStatus() != BatchStatus.FAILED) {
                logger.info("Job {} (fecha {}) reanudado automaticamente en el intento {}: ejecucion {} termino con estado {}",
                        nombreJob, fecha, intento, nueva.getId(), nueva.getStatus());
            } else if (intento < maxIntentos) {
                programarReintento(nombreJob, fecha, nueva.getId(), intento + 1);
            } else {
                logger.error("Job {} (fecha {}) sigue FAILED tras {} reintentos automaticos (ultima ejecucion {}): requiere "
                                + "intervencion manual. Corregir la causa y reanudar con POST /api/core/batch/{}/reanudar?fecha={}",
                        nombreJob, fecha, maxIntentos, nueva.getId(), nombreJob, fecha);
            }
        } catch (EjecucionBatchDuplicadaException ex) {
            logger.info("Job {} (fecha {}): reintento automatico cancelado: {}", nombreJob, fecha, ex.getMessage());
        } catch (RuntimeException ex) {
            logger.error("Job {} (fecha {}): fallo el reintento automatico {}: {}. Requiere intervencion manual "
                    + "(POST /api/core/batch/{}/reanudar?fecha={})", nombreJob, fecha, intento, ex.getMessage(), nombreJob, fecha, ex);
        }
    }

    // Reanuda la JobInstance desde su ejecucion fallida: nueva JobExecution de la misma instancia que retoma el
    // estado fallido. Se hace con run() y los mismos parametros (sobre una instancia cuya ultima ejecucion termino
    // FAILED, Spring Batch la trata como restart), que aplica las verificaciones de "ya en ejecucion / ya completada"
    // del JobRepository. Esas verificaciones no bastan con varias replicas: si dos revisan a la vez antes de que la otra
    // cree su ejecucion, ambas pasan (se probo: dos ejecuciones nuevas). Por eso antes se reclama la reanudacion de la
    // ejecucion fallida en batch_reanudaciones; solo una replica gana y la otra recibe EjecucionBatchDuplicada (409).
    private JobExecution reiniciar(String nombreJob, LocalDate fecha, JobExecution fallida) {
        reclamarReanudacion(nombreJob, fecha, fallida);
        try {
            return jobOperator.run(buscarJob(nombreJob), parametros(fecha));
        } catch (JobInstanceAlreadyCompleteException ex) {
            throw new EjecucionBatchDuplicadaException("El job " + nombreJob + " ya se completo para la fecha " + fecha);
        } catch (JobExecutionAlreadyRunningException ex) {
            throw new EjecucionBatchDuplicadaException("El job " + nombreJob + " ya esta en ejecucion para la fecha " + fecha);
        } catch (Exception ex) {
            // La ejecucion en curso de otra replica llega envuelta (por ejemplo en JobRestartException), o como
            // otra falla de la carrera: si ya hay una ejecucion posterior a la fallida, la reanudo otra replica.
            if (causadaPorEjecucionEnCurso(ex) || hayEjecucionPosterior(fallida)) {
                logger.info("Job {} (fecha {}) ya lo esta reanudando otra replica: {}: {}", nombreJob, fecha,
                        ex.getClass().getSimpleName(), ex.getMessage());
                throw new EjecucionBatchDuplicadaException("El job " + nombreJob + " para la fecha " + fecha
                        + " ya lo esta reanudando otra instancia");
            }
            // No se creo ninguna ejecucion nueva: se libera el reclamo para poder volver a reanudarla.
            liberarReanudacion(fallida);
            throw new IllegalStateException("No se pudo reanudar el job " + nombreJob + ": " + ex.getMessage(), ex);
        }
    }

    // Reclama la reanudacion de esta ejecucion fallida (INSERT con su id como clave primaria, en su propia transaccion):
    // si otra replica o un /reanudar simultaneo ya la reclamo, la clave duplicada lo impide y se responde 409.
    private void reclamarReanudacion(String nombreJob, LocalDate fecha, JobExecution fallida) {
        try {
            reanudacionRepository.saveAndFlush(new ReanudacionBatch(fallida.getId(), nombreJob, fecha.toString()));
        } catch (DataIntegrityViolationException ex) {
            throw new EjecucionBatchDuplicadaException("El job " + nombreJob + " para la fecha " + fecha
                    + " ya lo esta reanudando otra instancia");
        }
    }

    private void liberarReanudacion(JobExecution fallida) {
        try {
            reanudacionRepository.deleteById(fallida.getId());
        } catch (RuntimeException ex) {
            logger.warn("No se pudo liberar el reclamo de reanudacion de la ejecucion {}: {}", fallida.getId(), ex.getMessage());
        }
    }

    private boolean causadaPorEjecucionEnCurso(Throwable ex) {
        for (Throwable causa = ex; causa != null; causa = causa.getCause()) {
            if (causa instanceof JobExecutionAlreadyRunningException) {
                return true;
            }
        }
        return false;
    }

    private boolean hayEjecucionPosterior(JobExecution fallida) {
        try {
            JobExecution ultima = jobRepository.getLastJobExecution(fallida.getJobInstance());
            return ultima != null && ultima.getId() > fallida.getId();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private Job buscarJob(String nombreJob) {
        Job job = jobs.get(nombreJob);
        if (job == null) {
            throw new JobNoEncontradoException("No existe el job " + nombreJob + "; disponibles: " + jobs.keySet());
        }
        return job;
    }

    private JobInstance buscarInstancia(String nombreJob, LocalDate fecha) {
        Job job = buscarJob(nombreJob);
        JobInstance instancia = jobRepository.getJobInstance(job.getName(), parametros(fecha));
        if (instancia == null) {
            throw new InstanciaBatchNoEncontradaException("El job " + nombreJob + " no se ha ejecutado para la fecha " + fecha);
        }
        return instancia;
    }

    private JobParameters parametros(LocalDate fecha) {
        return new JobParametersBuilder().addString("fecha", fecha.toString()).toJobParameters();
    }

    private EjecucionBatchDTO dto(String nombreJob, LocalDate fecha, JobExecution ejecucion, int reintentos) {
        return new EjecucionBatchDTO(nombreJob, fecha.toString(), ejecucion.getId(), ejecucion.getStatus().name(),
                ejecucion.getExitStatus().getExitCode(), reintentos);
    }

    private boolean instanciaCreadaPorOtraReplica(Job job, JobParameters parametros) {
        try {
            return jobRepository.getJobInstance(job.getName(), parametros) != null;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private String mensajeOtraInstancia(String nombreJob, LocalDate fecha) {
        return "El job " + nombreJob + " para la fecha " + fecha + " ya lo esta iniciando otra instancia";
    }
}
