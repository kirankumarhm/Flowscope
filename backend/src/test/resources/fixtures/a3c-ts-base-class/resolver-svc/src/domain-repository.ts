import { DynamoDBDocumentClient, PutCommand } from '@aws-sdk/lib-dynamodb';

export interface RepoConfig { profileTableName: string }

export class DomainRepository {
  constructor(private readonly client: DynamoDBDocumentClient, private readonly config: RepoConfig) {}

  async save(item: Record<string, unknown>) {
    await this.client.send(new PutCommand({ TableName: this.config.profileTableName, Item: item }));
  }
}

export const profileRepository = (client: DynamoDBDocumentClient) =>
  new DomainRepository(client, { profileTableName: process.env.PROFILE_TABLE });
