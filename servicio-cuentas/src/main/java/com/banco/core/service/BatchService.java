package com.banco.core.service;

import com.banco.core.dto.EjecucionBatchDTO;
import com.banco.core.exception.EjecucionBatchDuplicadaException;
import com.banco.core.exception.JobNoEncontradoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;

// Los jobs no se ejecutan al arrancar (spring.batch.job.enabled=false): se disparan por endpoint.
// El parametro identificador "fecha" define la JobInstance; el JobRepository JDBC (compartido por las
// replicas) no deja ejecutar dos veces la misma instancia, ni en paralelo ni despues de completada.
@Service
public class BatchService {

    private static final Logger logger = LoggerFactory.getLogger(BatchService.class);

    private final JobOperator jobOperator;
    private final JobRepository jobRepository;
    private final Map<String, Job> jobs;

    public BatchService(JobOperator jobOperator, JobRepository jobRepository, Map<String, Job> jobs) {
        this.jobOperator = jobOperator;
        this.jobRepository = jobRepository;
        this.jobs = jobs;
    }

    public EjecucionBatchDTO ejecutar(String nombreJob, LocalDate fecha) {
        Job job = jobs.get(nombreJob);
        if (job == null) {
            throw new JobNoEncontradoException("No existe el job " + nombreJob + "; disponibles: " + jobs.keySet());
        }
        JobParameters parametros = new JobParametersBuilder()
                .addString("fecha", fecha.toString())
                .toJobParameters();
        try {
            // run() y no start(): en Spring Batch 6, start() ignora los parametros cuando el job define un
            // incrementer (RunIdIncrementer) y lanza "la siguiente instancia" con run.id, asi que la fecha no
            // identificaria la instancia y dos replicas calcularian el mismo run.id a la vez. run() usa la
            // JobInstance de exactamente estos parametros.
            JobExecution ejecucion = jobOperator.run(job, parametros);
            logger.info("Job {} (fecha {}) termino con estado {}", nombreJob, fecha, ejecucion.getStatus());
            return new EjecucionBatchDTO(nombreJob, fecha.toString(), ejecucion.getId(),
                    ejecucion.getStatus().name(), ejecucion.getExitStatus().getExitCode());
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
