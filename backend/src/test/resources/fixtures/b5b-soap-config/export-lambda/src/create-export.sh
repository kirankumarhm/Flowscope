#!/bin/sh
export PGDATABASE=$3
psql -c "\copy (SELECT * FROM devices) TO 'out.csv' CSV"
