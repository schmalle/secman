The multi-project Gradle setup is working perfectly:

:shared module: 52 tests passing (CrowdStrike API library)
:cli module: 13 tests passing (CLI commands)
:backendng module: Ready for integration

# Configure

secman config --client-id <id> --client-secret <secret>
secman config --show

# Query vulnerabilities

secman query --hostname server.example.com 
--severity critical 
--limit 50 
--format json 
--output /tmp/results.json

# Get help

secman help

java -jar src/cli/build/libs/cli-0.1.0-all.jar query --hostname EC2AMAZ-6167U5R


```
Development Configuration (localhost)

  Assuming:
  - Frontend runs on http://localhost:4321
  - Backend runs on http://localhost:8080

  Root URL:
  http://localhost:4321

  Home URL:
  http://localhost:4321

  Valid redirect URIs:
  http://localhost:8080/oauth/callback/keycloak
  http://localhost:4321/*
  (The backend OAuth callback + frontend wildcard for flexibility)

  Valid post logout redirect URIs:
  http://localhost:4321
  http://localhost:4321/login

  Web origins:
  http://localhost:4321
  http://localhost:8080
  (For CORS support)

  Production Configuration

  Replace localhost with your actual domain:

  Root URL:
  https://your-domain.com

  Home URL:
  https://your-domain.com

  Valid redirect URIs:
  https://your-domain.com/oauth/callback/keycloak
  https://your-domain.com/*

  Valid post logout redirect URIs:
  https://your-domain.com
  https://your-domain.com/login

  Web origins:
  https://your-domain.com

  Additional Backend Configuration Needed

  After creating the Keycloak client, you'll also need to update your Secman backend configuration in src/backendng/src/main/resources/application.yml:

  micronaut:
    security:
      oauth2:
        clients:
          keycloak:
            client-id: <your-client-id-from-keycloak>
            client-secret: <your-client-secret-from-keycloak>
            openid:
              issuer: http://localhost:8080/realms/<your-realm-name>
```
