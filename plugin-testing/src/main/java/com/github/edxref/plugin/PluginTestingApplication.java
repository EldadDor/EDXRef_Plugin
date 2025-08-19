package com.github.edxref.plugin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = "com.github.edxref")
public class PluginTestingApplication {

	public static void main(String[] args) {
		SpringApplication.run(PluginTestingApplication.class, args);
	}

}
