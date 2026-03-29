export MICRONAUT_ENVIRONMENTS=dev
export SECMAN_BACKEND_URL="op://test/secman/SECMAN_HOST"
export DB_HOST="op://test/secman/DB_HOST"
export DB_PORT="op://test/secman/DB_PORT"
export DB_NAME="op://test/secman/DB_NAME"
export DB_CONNECT="jdbc:mariadb://"+DB_HOST+":"+DB_PORT+"/"+DB_NAME


gradle :backendng:clean backendeng:run
