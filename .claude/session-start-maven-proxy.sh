#!/bin/bash
# Session start hook: configure Maven proxy for Claude Code remote environments
# The environment provides an egress proxy with JWT auth.
# Java/Maven can't resolve DNS locally — must route through the proxy.
# JAVA_TOOL_OPTIONS proxy config fails because Java resolves DNS before connecting.
# Fix: create ~/.m2/settings.xml with proxy credentials + install mvn-proxy wrapper.

set -e

# Only run if we're in a proxied environment
if [ -z "$http_proxy" ]; then
  exit 0
fi

# Parse proxy details from http_proxy env var: http://user:pass@host:port
PROXY_HOST=$(echo "$http_proxy" | sed 's|http://.*@\(.*\):\([0-9]*\)$|\1|')
PROXY_PORT=$(echo "$http_proxy" | sed 's|http://.*@\(.*\):\([0-9]*\)$|\2|')
PROXY_USER=$(echo "$http_proxy" | sed 's|http://\([^:]*\):.*|\1|')
PROXY_PASS=$(echo "$http_proxy" | sed 's|http://[^:]*:\([^@]*\)@.*|\1|')

if [ -z "$PROXY_USER" ] || [ -z "$PROXY_PASS" ]; then
  exit 0
fi

# Create Maven settings.xml with proxy config
mkdir -p ~/.m2
cat > ~/.m2/settings.xml << XMLEOF
<settings>
  <proxies>
    <proxy>
      <id>egress-https</id>
      <active>true</active>
      <protocol>https</protocol>
      <host>${PROXY_HOST}</host>
      <port>${PROXY_PORT}</port>
      <username>${PROXY_USER}</username>
      <password>${PROXY_PASS}</password>
    </proxy>
    <proxy>
      <id>egress-http</id>
      <active>true</active>
      <protocol>http</protocol>
      <host>${PROXY_HOST}</host>
      <port>${PROXY_PORT}</port>
      <username>${PROXY_USER}</username>
      <password>${PROXY_PASS}</password>
    </proxy>
  </proxies>
</settings>
XMLEOF

# Install mvn-proxy wrapper that fixes JAVA_TOOL_OPTIONS interference
MVN_BIN=$(which mvn 2>/dev/null || echo "/opt/maven/bin/mvn")
cat > /usr/local/bin/mvn-proxy << WRAPEOF
#!/bin/bash
# Maven wrapper that fixes proxy auth for Claude Code remote environments.
# Use 'mvn-proxy' instead of 'mvn' to avoid DNS resolution and proxy auth issues.

PROXY_HOST=\$(echo "\$http_proxy" | sed 's|http://.*@\(.*\):\([0-9]*\)\$|\1|')
PROXY_PORT=\$(echo "\$http_proxy" | sed 's|http://.*@\(.*\):\([0-9]*\)\$|\2|')
PROXY_USER=\$(echo "\$http_proxy" | sed 's|http://\([^:]*\):.*|\1|')
PROXY_PASS=\$(echo "\$http_proxy" | sed 's|http://[^:]*:\([^@]*\)@.*|\1|')

unset JAVA_TOOL_OPTIONS
export MAVEN_OPTS="-Dhttps.proxyHost=\$PROXY_HOST -Dhttps.proxyPort=\$PROXY_PORT -Dhttps.proxyUser=\$PROXY_USER -Dhttps.proxyPassword=\$PROXY_PASS -Dhttp.proxyHost=\$PROXY_HOST -Dhttp.proxyPort=\$PROXY_PORT -Dhttp.proxyUser=\$PROXY_USER -Dhttp.proxyPassword=\$PROXY_PASS -Djdk.http.auth.tunneling.disabledSchemes= -Djdk.http.auth.proxying.disabledSchemes="

exec ${MVN_BIN} -Daether.connector.basic.threads=1 "\$@"
WRAPEOF
chmod +x /usr/local/bin/mvn-proxy
