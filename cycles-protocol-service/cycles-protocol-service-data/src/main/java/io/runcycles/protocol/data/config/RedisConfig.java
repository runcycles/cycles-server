package io.runcycles.protocol.data.config;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import redis.clients.jedis.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Cycles Protocol v0.1.24 */
@Configuration
public class RedisConfig {
    private static final Logger LOG = LoggerFactory.getLogger(RedisConfig.class);
    
    @Value("${redis.host:localhost}") private String host;
    @Value("${redis.port:6379}") private int port;
    @Value("${redis.password:}") private String password;
    @Value("${redis.pool.max-total:128}") private int poolMaxTotal;
    @Value("${redis.pool.max-idle:32}") private int poolMaxIdle;
    @Value("${redis.pool.min-idle:16}") private int poolMinIdle;
    @Value("${redis.pool.max-wait-ms:2000}") private int poolMaxWaitMs;

    @Bean
    public JedisPool jedisPool() {
        LOG.info("Cycles Protocol v0.1.24 - Initializing Redis: {}:{}", host, port);
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(poolMaxTotal);
        config.setMaxIdle(poolMaxIdle);
        config.setMinIdle(poolMinIdle);
        config.setBlockWhenExhausted(true);
        config.setMaxWait(Duration.ofMillis(poolMaxWaitMs));
        config.setTestOnBorrow(false);
        config.setTestWhileIdle(true);
        config.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));
        config.setMinEvictableIdleTime(Duration.ofMinutes(5));
        return password.isEmpty() ? new JedisPool(config, host, port, 2000) :
                                    new JedisPool(config, host, port, 2000, password);
    }
    
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
    
    @Bean(name = "reserveLuaScript")
    public String reserveLuaScript() throws IOException {
        return loadLuaScript("lua/reserve.lua");
    }
    
    @Bean(name = "commitLuaScript")
    public String commitLuaScript() throws IOException {
        return loadLuaScript("lua/commit.lua");
    }
    
    @Bean(name = "releaseLuaScript")
    public String releaseLuaScript() throws IOException {
        return loadLuaScript("lua/release.lua");
    }
    
    @Bean(name = "extendLuaScript")
    public String extendLuaScript() throws IOException {
        return loadLuaScript("lua/extend.lua");
    }

    @Bean(name = "eventLuaScript")
    public String eventLuaScript() throws IOException {
        return loadLuaScript("lua/event.lua");
    }

    @Bean(name = "expireLuaScript")
    public String expireLuaScript() throws IOException {
        return loadLuaScript("lua/expire.lua");
    }

    private String loadLuaScript(String path) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
