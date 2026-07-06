package com.docflow.docflow_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DocflowBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(DocflowBackendApplication.class, args);
	}

}
