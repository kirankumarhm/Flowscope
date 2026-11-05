#!/bin/sh
aws s3 cp out.csv s3://$EXPORT_BUCKET/devices.csv --no-progress
psql -c "INSERT INTO export (id) VALUES (1)"
