package com.example.onepayment.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AppConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}
}
