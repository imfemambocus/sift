package dev.emambocus.sift;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SiftApplication {

	public static void main(String[] args) {
		SpringApplication.run(SiftApplication.class, args);
	}

}
