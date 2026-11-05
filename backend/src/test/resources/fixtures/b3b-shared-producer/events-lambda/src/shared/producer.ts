import { Kafka, Producer } from 'kafkajs';

export class EventProducer {
  private readonly producer: Producer;
  private readonly commandTopic: string;
  private readonly eventTopic: string;

  constructor() {
    this.producer = new Kafka({ brokers: [] }).producer();
    this.commandTopic = process.env.KAFKA_TOPIC_COMMAND;
    this.eventTopic = process.env.KAFKA_TOPIC_EVENT;
  }

  private async produce(topic: string, value: string): Promise<string> {
    console.debug(`sending [topic:${topic}]`);
    await this.producer.send({ topic, messages: [{ value }] });
    return 'OK';
  }

  public async produceCommand(value: string): Promise<string> {
    return this.produce(this.commandTopic, value);
  }

  public async produceEvent(value: string): Promise<string> {
    return this.produce(this.eventTopic, value);
  }
}
