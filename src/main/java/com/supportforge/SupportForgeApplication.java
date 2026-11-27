package com.supportforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;


@SpringBootApplication
@ConfigurationPropertiesScan
public class SupportForgeApplication {


	public static void main(String[] args) {

		SpringApplication.run(SupportForgeApplication.class, args);
	}

}
