#!/bin/bash
set -e
echo "Building Cycles Protocol Server v0.1.24"
echo "======================================"
mvn clean install
echo ""
echo "✅ Build complete!"
echo "Run: java -jar cycles-protocol-service-api/target/cycles-protocol-service-api-0.1.24.jar"
