package com.chargeflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** ChargeFlow: EV charging hub slot allocator. Open http://localhost:8080 after start-up. */
@SpringBootApplication
public class ChargeFlowApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChargeFlowApplication.class, args);
    }
}
