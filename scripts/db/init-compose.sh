#!/bin/sh
(
    set -eu

    : "${DB_PASSWORD:?DB_PASSWORD must be set for database initialization}"

    psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
        --set=ON_ERROR_STOP=1 --set=db_password="$DB_PASSWORD" \
        --file=/setup/setup-u1.sql --file=/setup/setup-u2-u4.sql
)
