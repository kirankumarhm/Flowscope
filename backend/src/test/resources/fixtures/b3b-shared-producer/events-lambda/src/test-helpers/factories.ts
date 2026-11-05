import { SelfManagedKafkaEvent } from 'aws-lambda';

export const kafkaEvent = (): SelfManagedKafkaEvent => ({ records: {} } as SelfManagedKafkaEvent);
