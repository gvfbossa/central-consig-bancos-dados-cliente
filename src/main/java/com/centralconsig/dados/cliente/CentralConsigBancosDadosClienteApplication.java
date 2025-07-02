package com.centralconsig.dados.cliente;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
		"com.centralconsig.dados.cliente",
		"com.centralconsig.core"
})
@EnableScheduling
public class CentralConsigBancosDadosClienteApplication {

	public static void main(String[] args) {
		SpringApplication.run(CentralConsigBancosDadosClienteApplication.class, args);
	}

}
