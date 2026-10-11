package com.banco.batch;

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.EnableJdbcJobRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.banco.batch", "com.banco.security", "com.banco.core"})
@EntityScan(basePackages = {"com.banco.batch.model", "com.banco.security.model", "com.banco.core.model"})
@EnableJpaRepositories(basePackages = {"com.banco.batch.repository", "com.banco.security.repository", "com.banco.core.repository"})
@EnableBatchProcessing
// JobRepository JDBC sobre banco_xyz (tablas BATCH_* de db/init/02-batch-schema.sql), compartido por todas
// las replicas: en Spring Batch 6, @EnableBatchProcessing solo deja un repositorio en memoria por JVM.
@EnableJdbcJobRepository(dataSourceRef = "dataSource", transactionManagerRef = "transactionManager")
public class BatchApplication {

	public static void main(String[] args) {
		SpringApplication.run(BatchApplication.class, args);
	}

}
