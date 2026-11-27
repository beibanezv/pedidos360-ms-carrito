package com.pedidos360.carrito.messaging;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "p360.kafka.enabled", havingValue = "true")
class InteraccionOutboxConfig {
  @Bean
  Clock relojInteracciones() {
    return Clock.systemUTC();
  }
}
