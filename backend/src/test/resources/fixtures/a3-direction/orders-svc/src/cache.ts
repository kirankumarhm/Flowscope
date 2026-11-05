import { DynamoDBDocumentClient, GetCommand } from '@aws-sdk/lib-dynamodb';

const seen = new Map<string, number>();

export async function cached(ddb: DynamoDBDocumentClient, id: string) {
  seen.delete(id);
  return ddb.send(new GetCommand({ TableName: process.env.ORDERS_TABLE, Key: { id } }));
}
