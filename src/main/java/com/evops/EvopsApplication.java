package com.evops;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan({"com.evops.ticket.mapper", "com.evops.ticket.agent.mapper",
        "com.evops.ticket.notify.mapper", "com.evops.ticket.mask.mapper",
        "com.evops.ticket.sensitive.mapper",
        "com.evops.auth.mapper", "com.evops.rbac.mapper"})
public class EvopsApplication {
    public static void main(String[] args) {
        SpringApplication.run(EvopsApplication.class, args);
    }
}
