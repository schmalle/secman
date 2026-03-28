```
  # With 1Password:
  ./tests/e2e/run-e2e.sh

  # Manually:
  cd tests/e2e
  SECMAN_ADMIN_NAME=admin SECMAN_ADMIN_PASS=pass \
  SECMAN_USER_USER=user SECMAN_USER_PASS=pass \
  npx playwright test
```
