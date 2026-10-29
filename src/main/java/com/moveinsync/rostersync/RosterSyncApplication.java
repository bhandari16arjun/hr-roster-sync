package com.moveinsync.rostersync;

import com.moveinsync.rostersync.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
@EnableScheduling
@EnableCaching
public class RosterSyncApplication {

    public static void main(String[] args) {
        SpringApplication.run(RosterSyncApplication.class, args);
    }
}
