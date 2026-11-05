import { DynamoDBDocumentClient } from '@aws-sdk/lib-dynamodb';
import { BaseService } from './base-service';

export class StateService extends BaseService {
  constructor(client: DynamoDBDocumentClient) {
    super(client, process.env.STATE_TABLE);
  }
}
