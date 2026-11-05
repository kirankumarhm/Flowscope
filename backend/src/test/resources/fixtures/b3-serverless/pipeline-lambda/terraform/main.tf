resource "aws_lambda_function" "consumer" {
  function_name = "pipeline-consumer"
  handler       = "index.consumer"
}

resource "aws_lambda_function" "worker" {
  function_name = "pipeline-worker"
  handler       = "index.worker"
}

resource "aws_sqs_queue" "work" {
  name = "work-queue"
}

resource "aws_lambda_event_source_mapping" "kafka" {
  function_name = aws_lambda_function.consumer.arn
  topics        = ["orders-events"]
  self_managed_event_source {
    endpoints = { KAFKA_BOOTSTRAP_SERVERS = "broker:9092" }
  }
}

resource "aws_lambda_event_source_mapping" "work" {
  event_source_arn = aws_sqs_queue.work.arn
  function_name    = aws_lambda_function.worker.arn
}
