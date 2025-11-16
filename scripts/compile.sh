# Build backend (IMPORTANT: Use shadowJar task to create executable JAR)
git pull
./gradlew :cli:shadowJar
cd src/backendng
./gradlew clean shadowJar -x test
# JAR will be created at: build/libs/backendng-0.1-all.jar

# Build frontend
cd ../frontend
npm ci --production
npm run build
# Build output will be in: dist/ directory