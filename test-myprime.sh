#!/bin/bash
# test-myprime.sh

BASE_URL="http://localhost:8080/myprime"

echo "Testing MyPrime Fraud Detection API"
echo "==================================="

# Test 1: High Risk Scenario
echo -e "\n🔴 Test 1: High Risk Scenario"
curl -X POST "$BASE_URL/fraud/decision" \
  -H "Content-Type: application/json" \
  -d '{
    "transaction_id": "MP_001",
    "transaction_amount": 200000,
    "transaction_type": "NAGAD",
    "is_new_device": true,
    "is_new_beneficiary": true,
    "failed_logins_last_1hr": 3,
    "transaction_count_last_5_min": 4,
    "avg_transaction_amount_30d": 50000,
    "xgboost_ml_score": 0.85,
    "unstructured_ml_score": 0.78,
    "transaction_timestamp": "2026-01-13T14:30:00"
  }'

# Test 2: Medium Risk Scenario
echo -e "\n🟡 Test 2: Medium Risk Scenario"
curl -X POST "$BASE_URL/fraud/decision" \
  -H "Content-Type: application/json" \
  -d '{
    "transaction_id": "MP_002",
    "transaction_amount": 75000,
    "transaction_type": "BKASH",
    "is_new_device": true,
    "failed_logins_last_1hr": 1,
    "transaction_count_last_5_min": 1,
    "avg_transaction_amount_30d": 30000,
    "xgboost_ml_score": 0.65,
    "unstructured_ml_score": 0.55,
    "transaction_timestamp": "2026-01-13T10:15:00"
  }'

# Test 3: Low Risk Scenario
echo -e "\n🟢 Test 3: Low Risk Scenario"
curl -X POST "$BASE_URL/fraud/decision" \
  -H "Content-Type: application/json" \
  -d '{
    "transaction_id": "MP_003",
    "transaction_amount": 10000,
    "transaction_type": "ROCKET",
    "is_new_device": false,
    "is_new_beneficiary": false,
    "failed_logins_last_1hr": 0,
    "transaction_count_last_5_min": 1,
    "avg_transaction_amount_30d": 15000,
    "xgboost_ml_score": 0.15,
    "unstructured_ml_score": 0.20,
    "transaction_timestamp": "2026-01-13T15:45:00"
  }'

# Test 4: Health Check
echo -e "\n🏥 Test 4: Health Check"
curl -X GET "$BASE_URL/health"

# Test 5: Pre-defined Test Scenarios
echo -e "\n🧪 Test 5: Pre-defined Test Scenarios"
curl -X POST "$BASE_URL/test?scenario=high_risk"
echo ""
curl -X POST "$BASE_URL/test?scenario=medium_risk"
echo ""
curl -X POST "$BASE_URL/test?scenario=low_risk"