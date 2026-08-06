#!/bin/bash

if [ -z "$1" ] || [ -z "$2" ] || [ -z "$3" ]; then
    echo "Usage: $0 <hostname> <username> <password>"
    exit 1
fi

./gradlew :cli:run --args="query --hostname $1 severity CRITICAL,HIGH,MEDIUM,LOW --min-days-open 1 --username $2 --password $3 --save --backend-url http://127.0.0.1:8080"
