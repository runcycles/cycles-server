package io.runcycles.protocol.api;

import org.slf4j.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Cycles Protocol v0.1.24 - Main Application */
@SpringBootApplication
@ComponentScan(basePackages = "io.runcycles.protocol")
@EnableScheduling
public class CyclesProtocolApplication {
    private static final Logger LOG = LoggerFactory.getLogger(CyclesProtocolApplication.class);
    
    public static void main(String[] args) {
        LOG.info("==========================================================");
        LOG.info("Cycles Protocol Server v0.1.24");
        LOG.info("Budget Authority API with Overdraft/Debt Support");
        LOG.info("==========================================================");
        SpringApplication.run(CyclesProtocolApplication.class, args);
    }
}
