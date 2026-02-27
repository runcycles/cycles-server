#!/bin/bash
# Initialize sample budgets for Cycles Protocol v0.1.23
CLI="redis-cli"
echo "Initializing budgets for Cycles Protocol v0.1.23..."

# Create tenant budget
$CLI HMSET "budget:tenant:demo-corp:TOKENS" \
    allocated 1000000 \
    remaining 1000000 \
    reserved 0 \
    spent 0 \
    debt 0 \
    overdraft_limit 100000 \
    is_over_limit false

echo "✅ Sample budget created: tenant:demo-corp (1M tokens, 100K overdraft)"
