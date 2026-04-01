package io.runcycles.protocol.api;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;

/** Cycles Protocol v0.1.25 - Main Application */
@SpringBootApplication
@ComponentScan(basePackages = "io.runcycles.protocol")
@EnableScheduling
public class CyclesProtocolApplication {

    public static void main(String[] args) {
        SpringApplication.run(CyclesProtocolApplication.class, args);
    }

    @Component
    static class StartupBanner implements CommandLineRunner {
        private static final Logger LOG = LoggerFactory.getLogger(CyclesProtocolApplication.class);
        @Autowired(required = false) private BuildProperties buildProperties;

        @Override
        public void run(String... args) {
            LOG.info("==========================================================");
            String version = buildProperties != null ? buildProperties.getVersion() : "unknown";
            LOG.info("Cycles Protocol Server v{}", version);
            LOG.info("Budget Authority API with Overdraft/Debt Support");
            LOG.info("==========================================================");
        }
    }
}
