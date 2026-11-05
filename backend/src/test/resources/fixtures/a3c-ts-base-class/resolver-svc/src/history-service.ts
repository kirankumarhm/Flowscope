import { DynamoDBDocumentClient } from '@aws-sdk/lib-dynamodb';
import { BaseService } from './base-service';

export class HistoryService extends BaseService {
  constructor(client: DynamoDBDocumentClient) {
    super(client, process.env.HISTORY_TABLE);
  }
}
