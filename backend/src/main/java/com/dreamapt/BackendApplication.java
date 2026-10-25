package com.dreamapt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BackendApplication {

	public static void main(String[] args) {
		ConfigurableApplicationContext context = SpringApplication.run(BackendApplication.class, args);
		// batch 프로필은 Job 하나를 실행하고 끝난다. 종료 코드는 CollectJobCommandLineRunner가 정한다 (ADR 006)
		if (context.getEnvironment().matchesProfiles("batch")) {
			System.exit(SpringApplication.exit(context));
		}
	}

}
