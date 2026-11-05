import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, GetCommand, PutCommand } from '@aws-sdk/lib-dynamodb';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

export async function getOrder(id: string) {
  return ddb.send(new GetCommand({ TableName: process.env.ORDERS_TABLE, Key: { id } }));
}

export async function audit(entry: { id: string }) {
  const existing = await ddb.send(new GetCommand({ TableName: process.env.AUDIT_TABLE, Key: { id: entry.id } }));
  await ddb.send(new PutCommand({ TableName: process.env.AUDIT_TABLE, Item: { ...entry, existing } }));
}
