#!/bin/sh
# Runs inside LocalStack once it is ready: creates the orders queue and its dead-letter queue.
# A message received more than maxReceiveCount times without being deleted moves to the DLQ.
set -eu

QUEUE_NAME="${SQS_QUEUE_NAME:-ticketing-orders}"
DLQ_NAME="${QUEUE_NAME}-dlq"
MAX_RECEIVE_COUNT=3
ATTRIBUTES_FILE=/tmp/orders-queue-attributes.json

DLQ_URL=$(awslocal sqs create-queue --queue-name "$DLQ_NAME" \
  --attributes MessageRetentionPeriod=1209600 --query QueueUrl --output text)
DLQ_ARN=$(awslocal sqs get-queue-attributes --queue-url "$DLQ_URL" \
  --attribute-names QueueArn --query Attributes.QueueArn --output text)

cat > "$ATTRIBUTES_FILE" <<JSON
{"VisibilityTimeout":"60","ReceiveMessageWaitTimeSeconds":"20","MessageRetentionPeriod":"345600","RedrivePolicy":"{\"deadLetterTargetArn\":\"${DLQ_ARN}\",\"maxReceiveCount\":\"${MAX_RECEIVE_COUNT}\"}"}
JSON
awslocal sqs create-queue --queue-name "$QUEUE_NAME" --attributes "file://${ATTRIBUTES_FILE}" >/dev/null

echo "SQS ready: ${QUEUE_NAME} (DLQ ${DLQ_NAME}, maxReceiveCount=${MAX_RECEIVE_COUNT})"
