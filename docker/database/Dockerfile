FROM mariadb:10.11

# Install required packages for initialization
RUN apt-get update && apt-get install -y \
    wget \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Copy initialization scripts
COPY init/ /docker-entrypoint-initdb.d/
COPY config/my.cnf /etc/mysql/conf.d/secman.cnf

# Set proper permissions
RUN chmod +x /docker-entrypoint-initdb.d/*.sql
RUN chmod +x /docker-entrypoint-initdb.d/*.sh

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD mysqladmin ping -h localhost -u ${MYSQL_USER} -p${MYSQL_PASSWORD} || exit 1

# Expose port
EXPOSE 3306