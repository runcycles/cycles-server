#!/bin/bash
# Complete generation script for Cycles Protocol v0.1.23

cd /home/claude/cycles-protocol-server

python3 << 'PYFULL'
import os
B = "/home/claude/cycles-protocol-server"
V = "0.1.23"

def w(p, c):
    with open(f"{B}/{p}", 'w') as f:
        f.write(c)

# DATA LAYER - Config, Exception, Service, Repository

# RedisConfig
w("cycles-protocol-data/src/main/java/com/cycles/protocol/data/config/RedisConfig.java", f'''package com.cycles.protocol.data.config;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import redis.clients.jedis.*;
import java.io.*;

/** Cycles Protocol v{V} */
@Configuration
public class RedisConfig {{
    private static final Logger LOG = LoggerFactory.getLogger(RedisConfig.class);
    
    @Value("${{redis.host:localhost}}") private String host;
    @Value("${{redis.port:6379}}") private int port;
    @Value("${{redis.password:}}") private String password;
    
    @Bean
    public JedisPool jedisPool() {{
        LOG.info("Cycles Protocol v{V} - Initializing Redis: {{}}:{{}}", host, port);
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(50);
        config.setMaxIdle(10);
        config.setMinIdle(5);
        return password.isEmpty() ? new JedisPool(config, host, port, 2000) : 
                                    new JedisPool(config, host, port, 2000, password);
    }}
    
    @Bean
    public ObjectMapper objectMapper() {{
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }}
    
    @Bean(name = "reserveLuaScript")
    public String reserveLuaScript() throws IOException {{
        return loadLuaScript("lua/reserve.lua");
    }}
    
    @Bean(name = "commitLuaScript")
    public String commitLuaScript() throws IOException {{
        return loadLuaScript("lua/commit.lua");
    }}
    
    @Bean(name = "releaseLuaScript")
    public String releaseLuaScript() throws IOException {{
        return loadLuaScript("lua/release.lua");
    }}
    
    @Bean(name = "extendLuaScript")
    public String extendLuaScript() throws IOException {{
        return loadLuaScript("lua/extend.lua");
    }}
    
    private String loadLuaScript(String path) throws IOException {{
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {{
            return reader.lines().reduce("", (a, b) -> a + b + "\\n");
        }}
    }}
}}
''')

# CyclesProtocolException
w("cycles-protocol-data/src/main/java/com/cycles/protocol/data/exception/CyclesProtocolException.java", f'''package com.cycles.protocol.data.exception;

import com.cycles.protocol.model.Enums;
import lombok.Getter;
import java.util.Map;

/** Cycles Protocol v{V} */
@Getter
public class CyclesProtocolException extends RuntimeException {{
    private final Enums.ErrorCode errorCode;
    private final int httpStatus;
    private final Map<String, Object> details;
    
    public CyclesProtocolException(Enums.ErrorCode errorCode, String message, int httpStatus) {{
        this(errorCode, message, httpStatus, null);
    }}
    
    public CyclesProtocolException(Enums.ErrorCode errorCode, String message, int httpStatus, Map<String, Object> details) {{
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
        this.details = details;
    }}
    
    public static CyclesProtocolException notFound(String resourceId) {{
        return new CyclesProtocolException(Enums.ErrorCode.NOT_FOUND, "Resource not found: " + resourceId, 404);
    }}
    
    public static CyclesProtocolException budgetExceeded(String scope) {{
        return new CyclesProtocolException(Enums.ErrorCode.BUDGET_EXCEEDED, "Budget exceeded for: " + scope, 409);
    }}
    
    public static CyclesProtocolException overdraftLimitExceeded(String scope) {{
        return new CyclesProtocolException(Enums.ErrorCode.OVERDRAFT_LIMIT_EXCEEDED, "Overdraft limit exceeded for: " + scope, 409);
    }}
    
    public static CyclesProtocolException debtOutstanding(String scope) {{
        return new CyclesProtocolException(Enums.ErrorCode.DEBT_OUTSTANDING, "Outstanding debt blocks new reservation for: " + scope, 409);
    }}
}}
''')

# ScopeDerivationService
w("cycles-protocol-data/src/main/java/com/cycles/protocol/data/service/ScopeDerivationService.java", f'''package com.cycles.protocol.data.service;

import com.cycles.protocol.model.Subject;
import org.springframework.stereotype.Service;
import java.util.*;

/** Cycles Protocol v{V} */
@Service
public class ScopeDerivationService {{
    public List<String> deriveScopes(Subject subject) {{
        List<String> scopes = new ArrayList<>();
        StringBuilder path = new StringBuilder();
        
        if (subject.getTenant() != null) {{
            append(scopes, path, "tenant", subject.getTenant());
        }}
        if (subject.getWorkspace() != null) {{
            append(scopes, path, "workspace", subject.getWorkspace());
        }}
        if (subject.getApp() != null) {{
            append(scopes, path, "app", subject.getApp());
        }}
        if (subject.getWorkflow() != null) {{
            append(scopes, path, "workflow", subject.getWorkflow());
        }}
        if (subject.getAgent() != null) {{
            append(scopes, path, "agent", subject.getAgent());
        }}
        if (subject.getToolset() != null) {{
            append(scopes, path, "toolset", subject.getToolset());
        }}
        
        return scopes;
    }}
    
    private void append(List<String> scopes, StringBuilder path, String key, String value) {{
        if (path.length() > 0) path.append("/");
        path.append(key).append(":").append(value);
        scopes.add(path.toString());
    }}
}}
''')

print(f"✅ Generated data layer config, exception, and service for v{V}")
PYFULL

echo "✅ Generation complete for v0.1.23"
