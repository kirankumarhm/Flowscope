import { SQSEvent } from 'aws-lambda';
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, PutCommand } from '@aws-sdk/lib-dynamodb';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

export const worker = async (event: SQSEvent) => {
  await ddb.send(new PutCommand({ TableName: process.env.RESULTS_TABLE, Item: { id: '1' } }));
};
