import { SQSClient, SendMessageCommand } from '@aws-sdk/client-sqs';

const sqs = new SQSClient({});

export async function replay(body: string) {
  const queueUrl = `https://sqs.${process.env.STACK_AWS_REGION}.amazonaws.com/${process.env.ACCOUNT}/${process.env.SQS_OUTPUT_QUEUE_NAME}`;
  await sqs.send(new SendMessageCommand({ QueueUrl: queueUrl, MessageBody: body }));
}
