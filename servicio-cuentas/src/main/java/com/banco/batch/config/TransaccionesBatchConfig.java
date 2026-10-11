package com.banco.batch.config;

import com.banco.batch.decider.TransaccionResultadoDecider;
import com.banco.batch.listener.LoggingJobExecutionListener;
import com.banco.batch.listener.LoggingSkipListener;
import com.banco.batch.listener.LoggingStepExecutionListener;
import com.banco.batch.model.Transaccion;
import com.banco.batch.partition.TransaccionPartitioner;
import com.banco.batch.policy.TransaccionSkipPolicy;
import com.banco.batch.processor.TransaccionProcessor;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDate;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.RunIdIncrementer;
import org.springframework.batch.core.partition.PartitionHandler;
import org.springframework.batch.core.partition.support.TaskExecutorPartitionHandler;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.database.JpaItemWriter;
import org.springframework.batch.infrastructure.item.database.builder.JpaItemWriterBuilder;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class TransaccionesBatchConfig{
    @Bean
    @StepScope
    public FlatFileItemReader<Transaccion> transaccionReader(
            @Value("#{stepExecutionContext['start']}") Long start,
            @Value("#{stepExecutionContext['end']}") Long end,
            @Value("${batch.transacciones.archivo}") Resource archivo) {
        return new FlatFileItemReaderBuilder<Transaccion>()
                .name("transaccionFileReader")
                .resource(archivo)
                // Sin estado guardado: al reanudar un job FAILED cada particion vuelve a leer su rango completo
                // (start/end); el writer hace merge por id, asi que releer no duplica transacciones.
                .saveState(false)
                .linesToSkip((int) (1 + start)) // salta el encabezado y las filas de particiones previas
                .maxItemCount((int) (end - start))
                .delimited()
                .names("id", "fecha", "monto", "tipo")
                .fieldSetMapper(fieldSet -> {
                    Transaccion t = new Transaccion();
                    t.setId(fieldSet.readLong("id"));
                    t.setFecha(LocalDate.parse(fieldSet.readString("fecha")));
                    t.setMonto(fieldSet.readDouble("monto"));
                    t.setTipo(fieldSet.readString("tipo"));
                    return t;
                })
                .build();
    }

    @Bean
    public TransaccionProcessor transaccionProcessor(){
        return new TransaccionProcessor();
    }

    @Bean
    public JpaItemWriter<Transaccion> transaccionWriter(EntityManagerFactory emf){
        return new JpaItemWriterBuilder<Transaccion>()
            .entityManagerFactory(emf)
            .build();
    }

    @Bean
    public TaskExecutor transaccionTaskExecutor(){
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(3);
        executor.setThreadNamePrefix("transaccion-hilo-");
        // Con 3 hilos fijos y chunks de 5, una cola de 10 absorbe picos sin crecer sin límite,
        // acorde al volumen de datos de este proyecto (archivos CSV pequeños).
        executor.setQueueCapacity(10);
        executor.initialize();
        return executor;
    }

    @Bean
    public LoggingSkipListener<Transaccion, Transaccion> transaccionSkipListener() {
        return new LoggingSkipListener<>();
    }

    @Bean
    public LoggingStepExecutionListener transaccionStepExecutionListener() {
        return new LoggingStepExecutionListener();
    }

    @Bean
    public LoggingJobExecutionListener loggingJobExecutionListener() {
        return new LoggingJobExecutionListener();
    }

    @Bean
    public Step transaccionStep(JobRepository jobRepository,
                                PlatformTransactionManager tx,
                                FlatFileItemReader<Transaccion> reader,
                                TransaccionProcessor processor,
                                JpaItemWriter<Transaccion> writer,
                                LoggingSkipListener<Transaccion, Transaccion> transaccionSkipListener,
                                LoggingStepExecutionListener transaccionStepExecutionListener,
                                @Value("${batch.transacciones.skip-limite}") int skipLimite,
                                @Value("${batch.transacciones.retry-limite}") int retryLimite) {
        return new StepBuilder("transaccionStep", jobRepository)
                // Al reanudar un job que fallo en el decider (CRITICO), las particiones ya estaban COMPLETED:
                // sin esto no se volverian a ejecutar y el decider no veria el archivo corregido.
                .allowStartIfComplete(true)
                .<Transaccion, Transaccion>chunk(5, tx)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .faultTolerant()
                .retryLimit(retryLimite)
                .retry(TransientDataAccessException.class)
                .skipPolicy(new TransaccionSkipPolicy(skipLimite))
                .listener(transaccionSkipListener)
                .listener(transaccionStepExecutionListener)
                .build();
    }

    @Bean
    public TransaccionPartitioner transaccionPartitioner(@Value("${batch.transacciones.archivo}") Resource archivo) {
        return new TransaccionPartitioner(archivo);
    }

    @Bean
    public PartitionHandler transaccionPartitionHandler(TaskExecutor transaccionTaskExecutor,
                                                         Step transaccionStep) {
        TaskExecutorPartitionHandler partitionHandler = new TaskExecutorPartitionHandler();
        partitionHandler.setTaskExecutor(transaccionTaskExecutor);
        partitionHandler.setStep(transaccionStep);
        partitionHandler.setGridSize(3);
        return partitionHandler;
    }

    @Bean
    public Step transaccionPartitionStep(JobRepository jobRepository,
                                         PartitionHandler transaccionPartitionHandler,
                                         TransaccionPartitioner transaccionPartitioner) {
        return new StepBuilder("transaccionPartitionStep", jobRepository)
                // Se vuelve a ejecutar al reanudar el job (ver transaccionStep).
                .allowStartIfComplete(true)
                .partitioner("transaccionStep", transaccionPartitioner)
                .partitionHandler(transaccionPartitionHandler)
                .build();
    }

    @Bean
    public TransaccionResultadoDecider transaccionResultadoDecider() {
        return new TransaccionResultadoDecider();
    }

    @Bean
    public Job transaccionesJob(JobRepository jobRepository,
                                Step transaccionPartitionStep,
                                TransaccionResultadoDecider transaccionResultadoDecider,
                                LoggingJobExecutionListener loggingJobExecutionListener) {
        return new JobBuilder("transaccionesJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .listener(loggingJobExecutionListener)
                .start(transaccionPartitionStep)
                .next(transaccionResultadoDecider)
                .on("OK").end()
                .from(transaccionResultadoDecider).on("ADVERTENCIA").end("COMPLETED WITH WARNINGS")
                .from(transaccionResultadoDecider).on("CRITICO").fail()
                .end()
                .build();
    }
}