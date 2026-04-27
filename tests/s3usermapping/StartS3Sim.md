```shell
# 1. Start S3Mock
docker run -p 9090:9090 adobe/s3mock

# 2. Create a bucket
aws s3api create-bucket --bucket mapping --endpoint-url http://localhost:9090

# 3. Upload a file
aws s3api put-object --bucket mapping --key accounts.json --body ./accounts.json --endpoint-url http://localhost:9090

# 4. Download the file
aws s3api get-object --bucket mapping --key my-file --endpoint-url http://localhost:9090 output-file
```

# Start S3Mock and test

docker run -p 9090:9090 adobe/s3mock
aws s3api create-bucket --bucket test --endpoint-url http://localhost:9090
aws s3api put-object --bucket test --key mappings.csv --body ./test-mappings.csv --endpoint-url http://localhost:9090

## secman CLI with S3Mock

```shell
# List bucket contents
./scriptpp/secman manage-user-mappings list-bucket --bucket test --endpoint-url http://localhost:9090

# Dry-run import (validate without creating mappings)
./scriptpp/secman manage-user-mappings import-s3 --bucket test --key mappings.csv --endpoint-url http://localhost:9090 --dry-run

# Actual import
./scriptpp/secman manage-user-mappings import-s3 --bucket test --key mappings.csv --endpoint-url http://localhost:9090

# Using AWS_ENDPOINT_URL environment variable instead of --endpoint-url
export AWS_ENDPOINT_URL=http://localhost:9090
./scriptpp/secman manage-user-mappings list-bucket --bucket mapping
./scriptpp/secman manage-user-mappings import-s3 --bucket test --key mappings.csv --dry-run
```
