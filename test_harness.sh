curl -X POST http://localhost:8081/api/harness/intent \
  -H "Content-Type: application/json" \
  -d '{
    "intent": "Find users in Miami."
  }'
