package dev.rashed.dispatch;

import org.springframework.boot.SpringApplication;

public class TestDispatchApplication {

    public static void main(String[] args) {
        SpringApplication.from(DispatchApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
