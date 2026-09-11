package cl.duoc.barriodigital.requests;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "cl.duoc.barriodigital")
public class RequestsApplication {
    public static void main(String[] args) {
        SpringApplication.run(RequestsApplication.class, args);
    }
}
