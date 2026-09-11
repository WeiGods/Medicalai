package com.medicalai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MedicalaiApplication {
  public static void main(String[] args) { SpringApplication.run(MedicalaiApplication.class, args); }
}
