#!/usr/bin/env bash
# Runs automatically inside the LocalStack container on startup
# (mounted to /etc/localstack/init/ready.d/) to provision the
# SNS topic, SQS queue, and Dead Letter Queue described in the PRD.

set -euo pipefail

export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1

ENDPOINT="http://localhost:4566"

echo "[init-aws] Creating SNS topic..."
awslocal sns create-topic --name order-notifications --endpoint-url "$ENDPOINT"

echo "[init-aws] Creating DLQ..."
awslocal sqs create-queue --queue-name order-email-dlq --endpoint-url "$ENDPOINT"

DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url "$ENDPOINT/000000000000/order-email-dlq" \
  --attribute-names QueueArn --endpoint-url "$ENDPOINT" \
  --query "Attributes.QueueArn" --output text)

echo "[init-aws] Creating main queue with redrive policy -> DLQ..."
REDRIVE_POLICY="{\"deadLetterTargetArn\":\"${DLQ_ARN}\",\"maxReceiveCount\":\"3\"}"
awslocal sqs create-queue --queue-name order-email-queue \
  --attributes "{\"RedrivePolicy\":\"$(echo "$REDRIVE_POLICY" | sed 's/"/\\"/g')\"}" \
  --endpoint-url "$ENDPOINT"

QUEUE_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url "$ENDPOINT/000000000000/order-email-queue" \
  --attribute-names QueueArn --endpoint-url "$ENDPOINT" \
  --query "Attributes.QueueArn" --output text)

echo "[init-aws] Allowing SNS to publish to SQS..."
awslocal sqs set-queue-attributes \
  --queue-url "$ENDPOINT/000000000000/order-email-queue" \
  --attributes "{\"Policy\":\"{\\\"Version\\\":\\\"2012-10-17\\\",\\\"Statement\\\":[{\\\"Effect\\\":\\\"Allow\\\",\\\"Principal\\\":\\\"*\\\",\\\"Action\\\":\\\"sqs:SendMessage\\\",\\\"Resource\\\":\\\"${QUEUE_ARN}\\\"}]}\"}" \
  --endpoint-url "$ENDPOINT"

echo "[init-aws] Subscribing SQS queue to SNS topic..."
awslocal sns subscribe \
  --topic-arn arn:aws:sns:us-east-1:000000000000:order-notifications \
  --protocol sqs \
  --notification-endpoint "$QUEUE_ARN" \
  --endpoint-url "$ENDPOINT"

echo "[init-aws] Done. SNS topic + SQS queue + DLQ provisioned."
