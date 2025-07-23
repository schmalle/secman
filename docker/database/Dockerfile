FROM mariadb:11.4

# Copy custom configuration
COPY docker/database/my.cnf /etc/mysql/conf.d/custom.cnf

# Copy initialization scripts
COPY docker/database/init/ /docker-entrypoint-initdb.d/

# Set proper permissions
RUN chmod -R 755 /docker-entrypoint-initdb.d/

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD mysqladmin ping -h localhost -u $MYSQL_USER -p$MYSQL_PASSWORD || exit 1

# Expose port
EXPOSE 3306