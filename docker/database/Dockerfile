# Secman Database Container
# MariaDB 11.4 with pre-configured database and user
FROM mariadb:11.4

LABEL maintainer="secman"
LABEL description="Secman MariaDB database container"

# Non-secret defaults (override at runtime).
ENV MYSQL_DATABASE=secman
ENV MYSQL_USER=secman

# MYSQL_ROOT_PASSWORD / MYSQL_PASSWORD intentionally have NO baked default —
# a dev fallback here would eventually leak into a real deployment. The
# upstream mariadb image refuses to start without one of these set anyway.
# Inject them at `docker run -e` / compose `env_file` time. See docker/README.md.

# Copy initialization SQL (runs on first container start only)
COPY init.sql /docker-entrypoint-initdb.d/01-init.sql

# MariaDB default port
EXPOSE 3306

# Health check
HEALTHCHECK --interval=10s --timeout=5s --retries=12 --start-period=30s \
  CMD mariadb -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -e "SELECT 1" "$MYSQL_DATABASE" || exit 1
