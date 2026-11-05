import { SelfManagedKafkaEvent } from 'aws-lambda';

export const commandConsumer = async (event: SelfManagedKafkaEvent) => {
  return event.records;
};

export const healthCheck = async () => {
  return 'ok';
};
