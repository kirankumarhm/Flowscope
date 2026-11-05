import { DynamoDBDocumentClient, GetCommand, QueryCommand } from '@aws-sdk/lib-dynamodb';

export abstract class BaseService {
  protected readonly tableName: string;

  constructor(protected readonly client: DynamoDBDocumentClient, tableName: string) {
    this.tableName = tableName;
  }

  protected async byKey(key: Record<string, unknown>) {
    return this.client.send(new GetCommand({ TableName: this.tableName, Key: key }));
  }
}
