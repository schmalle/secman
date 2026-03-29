export MICRONAUT_ENVIRONMENTS=dev
export SECMAN_BACKEND_URL="op://test/secman/SECMAN_HOST"
gradle :backendng:clean backendeng:run
