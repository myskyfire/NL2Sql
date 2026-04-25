package com.nl2sql.web;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication(scanBasePackages = "com.nl2sql")
@MapperScan("com.nl2sql.**.mapper")
@EnableAsync // ✅ 启用异步支持
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
