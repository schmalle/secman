The multi-project Gradle setup is working perfectly:

:shared module: 52 tests passing (CrowdStrike API library)
:cli module: 13 tests passing (CLI commands)
:backendng module: Ready for integration

# Configure
secman config --client-id <id> --client-secret <secret>
secman config --show

# Query vulnerabilities
secman query --hostname server.example.com \
             --severity critical \
             --limit 50 \
             --format json \
             --output /tmp/results.json

# Get help
secman help

java -jar src/cli/build/libs/cli-0.1.0-all.jar query --hostname EC2AMAZ-6167U5R