import { Kafka, Producer } from 'kafkajs';

export class CommandProducer {
  private commandTopic: string | undefined;

  constructor(private producer: Producer) {
    this.commandTopic = process.env.KAFKA_TOPIC_COMMAND;
  }

  async sendCommand(value: string) {
    await this.produce(this.commandTopic, value);
  }

  private async produce(topic: string, value: string) {
    await this.producer.send({ topic, messages: [{ value }] });
  }
}
