package com.fluxbeam.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class HttpClientsConfig {
  @Bean
  RestClient restClient() {
    return RestClient.create();
  }

  @Bean
  WebClient webClient() {
    return WebClient.builder().build();
  }
}
