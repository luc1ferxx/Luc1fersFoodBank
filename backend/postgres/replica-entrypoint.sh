#!/bin/sh
# Entrypoint of a PostgreSQL hot standby on the official postgres image (docker-compose.yml profile "replica", and
# the replica of the replication tests). Runs as the postgres user.
#
# On an empty data directory it clones the primary with pg_basebackup (-R writes standby.signal and primary_conninfo,
# so the server starts as a streaming replica); afterwards it only starts the server. Settings:
#   PRIMARY_HOST, PRIMARY_PORT, PRIMARY_USER   the primary to clone and stream from (default db:5432, postgres)
#   PGPASSWORD                                 that user's password (the user needs the REPLICATION attribute)
#   PGDATA                                     data directory (default /var/lib/postgresql/data)
# Extra arguments are passed to postgres.
#
# No replication slot is created, so the primary keeps no WAL for this replica: one that stays stopped for long while
# the primary keeps writing can no longer catch up ("requested WAL segment ... has already been removed"). Delete its
# data volume and start it again to clone afresh.
set -eu

PGDATA="${PGDATA:-/var/lib/postgresql/data}"
PRIMARY_HOST="${PRIMARY_HOST:-db}"
PRIMARY_PORT="${PRIMARY_PORT:-5432}"
PRIMARY_USER="${PRIMARY_USER:-postgres}"

if [ ! -s "$PGDATA/PG_VERSION" ]; then
  echo "replica: cloning ${PRIMARY_HOST}:${PRIMARY_PORT} into ${PGDATA}"
  until pg_basebackup --host="$PRIMARY_HOST" --port="$PRIMARY_PORT" --username="$PRIMARY_USER" --no-password \
      --pgdata="$PGDATA" --write-recovery-conf --wal-method=stream --checkpoint=fast; do
    echo "replica: primary not reachable yet, retrying in 2s"
    # pg_basebackup removes what it wrote on failure; this also clears anything an interrupted run left behind.
    find "${PGDATA:?}" -mindepth 1 -delete
    sleep 2
  done
fi
chmod 0700 "$PGDATA"

# hot_standby_feedback: the primary keeps rows a running replica query still needs, so long reads are not cancelled
# for conflicting with recovery.
exec postgres -D "$PGDATA" -c hot_standby=on -c hot_standby_feedback=on "$@"
