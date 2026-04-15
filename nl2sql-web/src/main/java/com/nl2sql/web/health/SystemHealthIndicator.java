package com.nl2sql.web.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.net.HttpURLConnection;
import java.net.URL;

@Slf4j
@Component
public class SystemHealthIndicator implements HealthIndicator {
    
    @Autowired
    private DataSource dataSource;
    
    @Autowired
    private StringRedisTemplate redisTemplate;
    
    @Override
    public Health health() {
        Health.Builder builder = Health.up();
        
        // 检查数据库连接
        builder.withDetail("database", checkDatabase());
        
        // 检查Redis连接
        builder.withDetail("redis", checkRedis());
        
        // 检查Ollama服务
        builder.withDetail("ollama", checkOllama());
        
        return builder.build();
    }
    
    private String checkDatabase() {
        try {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return "UP";
        } catch (Exception e) {
            log.error("数据库健康检查失败", e);
            return "DOWN: " + e.getMessage();
        }
    }
    
    private String checkRedis() {
        try {
            redisTemplate.opsForValue().get("health_check");
            return "UP";
        } catch (Exception e) {
            log.error("Redis健康检查失败", e);
            return "DOWN: " + e.getMessage();
        }
    }
    
    private String checkOllama() {
        try {
            URL url = new URL("http://localhost:11434/api/tags");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int responseCode = conn.getResponseCode();
            conn.disconnect();
            
            return responseCode == 200 ? "UP" : "DOWN (HTTP " + responseCode + ")";
        } catch (Exception e) {
            log.error("Ollama健康检查失败", e);
            return "DOWN: " + e.getMessage();
        }
    }
}
