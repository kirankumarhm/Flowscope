import type { ArchNode } from '../types';

import awsLambda from './aws-lambda.svg?raw';
import awsDynamodb from './aws-dynamodb.svg?raw';
import awsS3 from './aws-s3.svg?raw';
import awsSqs from './aws-sqs.svg?raw';
import awsSns from './aws-sns.svg?raw';
import awsSecretsManager from './aws-secrets-manager.svg?raw';
import awsCognito from './aws-cognito.svg?raw';
import awsEventbridge from './aws-eventbridge.svg?raw';
import awsStepFunctions from './aws-step-functions.svg?raw';
import awsSystemsManager from './aws-systems-manager.svg?raw';
import awsKms from './aws-kms.svg?raw';
import awsKinesis from './aws-kinesis.svg?raw';
import awsSes from './aws-ses.svg?raw';
import springboot from './springboot.svg?raw';
import nodedotjs from './nodedotjs.svg?raw';
import go from './go.svg?raw';
import python from './python.svg?raw';
import nestjs from './nestjs.svg?raw';
import nextdotjs from './nextdotjs.svg?raw';
import docker from './docker.svg?raw';
import react from './react.svg?raw';
import angular from './angular.svg?raw';
import postgresql from './postgresql.svg?raw';
import mysql from './mysql.svg?raw';
import mariadb from './mariadb.svg?raw';
import mongodb from './mongodb.svg?raw';
import redis from './redis.svg?raw';
import elasticsearch from './elasticsearch.svg?raw';
import h2database from './h2database.svg?raw';
import apachecassandra from './apachecassandra.svg?raw';
import apachekafka from './apachekafka.svg?raw';
import firebase from './firebase.svg?raw';
import genericGlobe from './generic-globe.svg?raw';
import genericCloud from './generic-cloud.svg?raw';
import genericDatabase from './generic-database.svg?raw';
import genericServer from './generic-server.svg?raw';
import genericQueue from './generic-queue.svg?raw';
import genericBrowser from './generic-browser.svg?raw';

/** An icon for one architecture box. See NOTICE.md for sources and licences. */
export interface ArchIcon {
  /** SVG markup, used in the web view and embedded in non-AWS draw.io cells. */
  svg: string;
  /** draw.io's built-in AWS 4 resource icon, so AWS services export as native draw.io shapes. */
  drawio?: { resIcon: string; fill: string };
}

const aws = (svg: string, resIcon: string, fill: string): ArchIcon => ({ svg, drawio: { resIcon, fill } });

const AWS = {
  lambda: aws(awsLambda, 'lambda', '#ED7100'),
  dynamodb: aws(awsDynamodb, 'dynamodb', '#C925D1'),
  s3: aws(awsS3, 's3', '#7AA116'),
  sqs: aws(awsSqs, 'sqs', '#E7157B'),
  sns: aws(awsSns, 'sns', '#E7157B'),
  secrets: aws(awsSecretsManager, 'secrets_manager', '#DD344C'),
  cognito: aws(awsCognito, 'cognito', '#DD344C'),
  eventbridge: aws(awsEventbridge, 'eventbridge', '#E7157B'),
  stepFunctions: aws(awsStepFunctions, 'step_functions', '#E7157B'),
  systemsManager: aws(awsSystemsManager, 'systems_manager', '#E7157B'),
  kms: aws(awsKms, 'key_management_service', '#DD344C'),
  kinesis: aws(awsKinesis, 'kinesis_data_streams', '#8C4FFF'),
  ses: aws(awsSes, 'simple_email_service', '#DD344C'),
};

const logo = (svg: string): ArchIcon => ({ svg });

const UI: Record<string, ArchIcon> = {
  react: logo(react),
  angular: logo(angular),
  nextjs: logo(nextdotjs),
};

const SERVICE: Record<string, ArchIcon> = {
  'node-lambda': AWS.lambda,
  lambda: AWS.lambda,
  'spring-boot': logo(springboot),
  container: logo(docker),
  'node-service': logo(nodedotjs),
  javascript: logo(nodedotjs),
  nestjs: logo(nestjs),
  go: logo(go),
  'go-service': logo(go),
  python: logo(python),
  'python-service': logo(python),
};

// Data stores, queues and triggers, keyed by the store kind FlowScope detected.
const STORE: Record<string, ArchIcon> = {
  postgresql: logo(postgresql),
  mysql: logo(mysql),
  mariadb: logo(mariadb),
  mongodb: logo(mongodb),
  redis: logo(redis),
  elasticsearch: logo(elasticsearch),
  h2: logo(h2database),
  cassandra: logo(apachecassandra),
  dynamodb: AWS.dynamodb,
  s3: AWS.s3,
  sqs: AWS.sqs,
  sns: AWS.sns,
  schedule: AWS.eventbridge,
  eventbridge: AWS.eventbridge,
  kinesis: AWS.kinesis,
};

// Cloud services are named by FlowScope's own SDK catalog, so matching on the name is exact.
const CLOUD: [RegExp, ArchIcon][] = [
  [/secrets manager/i, AWS.secrets],
  [/cognito/i, AWS.cognito],
  [/\bsns\b/i, AWS.sns],
  [/\bsqs\b/i, AWS.sqs],
  [/step functions/i, AWS.stepFunctions],
  [/parameter store|systems manager/i, AWS.systemsManager],
  [/\bkms\b/i, AWS.kms],
  [/eventbridge/i, AWS.eventbridge],
  [/kinesis/i, AWS.kinesis],
  [/\bses\b/i, AWS.ses],
  [/\blambda\b/i, AWS.lambda],
  [/firebase/i, logo(firebase)],
];

const GENERIC = {
  globe: logo(genericGlobe),
  cloud: logo(genericCloud),
  database: logo(genericDatabase),
  server: logo(genericServer),
  queue: logo(genericQueue),
  browser: logo(genericBrowser),
};

export function iconFor(n: ArchNode): ArchIcon {
  const sub = n.subtype ?? '';
  switch (n.type) {
    case 'ui':
      return UI[sub] ?? GENERIC.browser;
    case 'service':
      return SERVICE[sub] ?? GENERIC.server;
    case 'kafka':
      return logo(apachekafka);
    case 'datastore':
      return STORE[sub] ?? GENERIC.database;
    case 'queue':
      return STORE[sub] ?? GENERIC.queue;
    case 'trigger':
      return STORE[sub] ?? AWS.eventbridge;
    case 'cloud':
    case 'external': {
      const hit = CLOUD.find(([re]) => re.test(n.label));
      if (hit) return hit[1];
      return n.type === 'cloud' ? GENERIC.cloud : GENERIC.globe;
    }
    default:
      return GENERIC.server;
  }
}

function base64(svg: string): string {
  const bytes = new TextEncoder().encode(svg);
  let bin = '';
  bytes.forEach((b) => {
    bin += String.fromCharCode(b);
  });
  return btoa(bin);
}

/** `data:` URI for Cytoscape background images. */
export function svgDataUri(svg: string): string {
  return `data:image/svg+xml;base64,${base64(svg)}`;
}

/** draw.io wants `data:image/svg+xml,<base64>`: a `;` would end the style value. */
export function drawioImage(svg: string): string {
  return `data:image/svg+xml,${base64(svg)}`;
}
