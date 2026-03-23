package io.runcycles.protocol.data.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisNoScriptException;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages Lua script SHA caches for EVALSHA optimization.
 * Scripts are loaded once at startup via SCRIPT LOAD; subsequent calls use EVALSHA
 * with automatic fallback to EVAL on NOSCRIPT (e.g., after Redis restart).
 */
@Service
public class LuaScriptRegistry implements InitializingBean {
    private static final Logger LOG = LoggerFactory.getLogger(LuaScriptRegistry.class);

    @Autowired private JedisPool jedisPool;
    @Autowired @Qualifier("reserveLuaScript") private String reserveScript;
    @Autowired @Qualifier("commitLuaScript") private String commitScript;
    @Autowired @Qualifier("releaseLuaScript") private String releaseScript;
    @Autowired @Qualifier("extendLuaScript") private String extendScript;
    @Autowired @Qualifier("eventLuaScript") private String eventScript;
    @Autowired @Qualifier("expireLuaScript") private String expireScript;

    private final ConcurrentHashMap<String, String> scriptShaCache = new ConcurrentHashMap<>();

    @Override
    public void afterPropertiesSet() {
        loadScripts();
    }

    public void loadScripts() {
        try (Jedis jedis = jedisPool.getResource()) {
            loadScript(jedis, "reserve", reserveScript);
            loadScript(jedis, "commit", commitScript);
            loadScript(jedis, "release", releaseScript);
            loadScript(jedis, "extend", extendScript);
            loadScript(jedis, "event", eventScript);
            loadScript(jedis, "expire", expireScript);
            LOG.info("Loaded {} Lua scripts via SCRIPT LOAD", scriptShaCache.size());
        } catch (Exception e) {
            LOG.warn("Failed to pre-load Lua scripts; will fall back to EVAL", e);
        }
    }

    private void loadScript(Jedis jedis, String name, String script) {
        String sha = jedis.scriptLoad(script);
        scriptShaCache.put(name, sha);
        LOG.debug("Loaded script '{}': sha={}", name, sha);
    }

    /**
     * Execute a Lua script using EVALSHA with automatic fallback to EVAL.
     */
    public Object eval(Jedis jedis, String scriptName, String scriptSource, String... args) {
        String sha = scriptShaCache.get(scriptName);
        if (sha != null) {
            try {
                return jedis.evalsha(sha, 0, args);
            } catch (JedisNoScriptException e) {
                LOG.debug("NOSCRIPT for '{}', reloading", scriptName);
                sha = jedis.scriptLoad(scriptSource);
                scriptShaCache.put(scriptName, sha);
                return jedis.evalsha(sha, 0, args);
            }
        }
        // No cached SHA — fall back to EVAL
        return jedis.eval(scriptSource, 0, args);
    }
}
