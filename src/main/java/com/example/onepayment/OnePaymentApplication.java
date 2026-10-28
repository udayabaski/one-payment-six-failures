package com.example.onepayment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class OnePaymentApplication {

	public static void main(String[] args) {
		SpringApplication.run(OnePaymentApplication.class, args);
	}
}
