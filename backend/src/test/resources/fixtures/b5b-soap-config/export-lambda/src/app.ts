import { spawnSync } from 'child_process';

export const handler = async () => {
  spawnSync('./create-export.sh', []);
  spawnSync('./transfer-export.sh', []);
};
