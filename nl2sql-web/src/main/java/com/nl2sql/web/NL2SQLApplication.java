package com.nl2sql.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.nl2sql")
public class NL2SQLApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(NL2SQLApplication.class, args);
        System.out.println("╔═══════════════════════════════════════╗");
        System.out.println("║                                       ║");
        System.out.println("║     DataMind AI 启动成功!             ║");
        System.out.println("║                                       ║");
        System.out.println("║  访问地址: http://localhost:8080      ║");
        System.out.println("║                                       ║");
        System.out.println("╚═══════════════════════════════════════╝");
    }
}
