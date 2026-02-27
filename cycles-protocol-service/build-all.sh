#!/bin/bash
set -e
echo "Building Cycles Protocol Server v0.1.23"
echo "======================================"
mvn clean install
echo ""
echo "✅ Build complete!"
echo "Run: java -jar cycles-protocol-api/target/cycles-protocol-api-0.1.23.jar"
