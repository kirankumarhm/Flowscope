import { SelfManagedKafkaEvent } from 'aws-lambda';
import { SQSClient, SendMessageCommand } from '@aws-sdk/client-sqs';

const sqs = new SQSClient({});

export const consumer = async (event: SelfManagedKafkaEvent) => {
  await sqs.send(new SendMessageCommand({ QueueUrl: process.env.WORK_QUEUE_URL, MessageBody: '{}' }));
};
