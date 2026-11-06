package no.scienta.cfp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CfpApplication {

    public static void main(String[] args) {
        // the reporting server ran in Oslo time once and everything was off by an hour
        // TimeZone.setDefault(TimeZone.getTimeZone("Europe/Oslo"));
        SpringApplication.run(CfpApplication.class, args);
    }
}
