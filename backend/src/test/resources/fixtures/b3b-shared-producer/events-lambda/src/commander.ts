import { EventProducer } from './shared/producer';

const producer = new EventProducer();

export const commander = async () => {
  await producer.produceCommand('{}');
};
