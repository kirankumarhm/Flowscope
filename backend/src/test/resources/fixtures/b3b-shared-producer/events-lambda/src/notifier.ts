import { EventProducer } from './shared/producer';

const producer = new EventProducer();

export const notifier = async () => {
  await producer.produceEvent('{}');
};
