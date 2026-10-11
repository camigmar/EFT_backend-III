package com.banco.pagos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
// Scheduling: ReconciliadorPagos revisa periodicamente los pagos con resultado incierto.
@EnableScheduling
public class ServicioPagosApplication {

	public static void main(String[] args) {
		SpringApplication.run(ServicioPagosApplication.class, args);
	}

}
