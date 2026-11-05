#!/bin/sh
set -e
psql -h "$DB_HOST" -d reporting -c "\copy (SELECT * FROM devices) TO 'out.csv' CSV"
aws s3 cp out.csv s3://exports-bucket/devices.csv
