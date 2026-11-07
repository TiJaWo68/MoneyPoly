package de.in.mp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MoneyPoly - Family Finance, Loans & Energy Hub.
 *
 * <p>Einstiegspunkt der Spring-Boot-Anwendung. Die fachliche Datenbankstruktur
 * wird beim Start automatisch über Flyway (db/migration) aufgebaut.</p>
 */
@SpringBootApplication
public class MoneyPolyApplication {

    public static void main(String[] args) {
        SpringApplication.run(MoneyPolyApplication.class, args);
    }
}
