````markdown name=custom-copilot-instructions.md
# Application Instructions

### Locations

The src directory contains two sub directories

- **frontend**
- **backend**



Welcome to the custom application! This project consists of two main components:
1. **Backend**: A Java-based backend located in the `src/backend` folder, using **SBT** and **Gradle** as build tools.
2. **Frontend**: A React-based frontend built with Astro, located in the `src/frontend` folder, using **npm** exclusively for dependency management and scripts.

Follow the instructions below to set up and run the application.

---

## Prerequisites
Ensure the following tools are installed on your system:
- **Java Development Kit (JDK)** (version 11 or above)
- **SBT** (Scala Build Tool) 
- **Gradle** (version 7 or above)
- **Node.js** (version 16 or above)
- **npm** (for managing frontend dependencies and scripts)

---

## Backend (Java with SBT and Gradle)
### Setup (SBT)
1. Navigate to the backend directory:
   ```bash
   cd src/backend
   ```
2. Compile the project using SBT:
   ```bash
   sbt compile
   ```
3. Configure the application:
   - Check the `application.conf` file in the `src/main/resources` directory for SBT-specific configurations.
   - Update any required environment variables or settings.

---

### Setup (Gradle)
1. Navigate to the backend directory:
   ```bash
   cd src/backend
   ```
2. Build the project using Gradle:
   ```bash
   gradle build
   ```
3. Configure the application:
   - Check the `application.properties` file in the `src/main/resources` directory for Gradle-specific configurations.
   - Update any required environment variables or settings.

---

### Running the Backend
#### With SBT
1. Run the backend server using SBT:
   ```bash
   sbt run
   ```
2. The backend server should now be running on `http://localhost:9000`.

#### With Gradle
1. Run the backend server using Gradle:
   ```bash
   gradle bootRun
   ```
2. The backend server should now be running on `http://localhost:9000`.

### Notes for Developers and Copilot
- **Where to Configure API Endpoints**: Backend API endpoints and configurations are defined in either `application.conf` (SBT) or `application.properties` (Gradle).
- **Testing**: Include test cases in the `src/test` directory. Use `sbt test` or `gradle test` to run them.
- **Debugging**: Add JVM debugging options in the build tools (e.g., `-Xdebug`).

---

## Frontend (React with Astro)
### Setup
1. Navigate to the frontend directory:
   ```bash
   cd src/frontend
   ```
2. Install the required dependencies using **npm**:
   ```bash
   npm install
   ```

### Running the Frontend
1. Start the Astro development server using **npm**:
   ```bash
   npm run dev
   ```
2. The frontend should now be running on `http://localhost:3000`.

### Notes for Developers and Copilot
- **Where to Configure API Endpoints**:
   - Backend API endpoints used by the frontend are configured in `src/frontend/src/config.js` (or similar).
   - Ensure the `BASE_URL` or `API_URL` is set to `http://localhost:8080` during development.
- **Testing**:
   - Add frontend tests (unit and integration) in the `src/tests` directory.
   - Use `npm run test` to run the tests.
- **Linting**:
   - Use `npm run lint` to check for code quality issues.
   - Fix issues before committing your code.

---

## Full Application Workflow
1. Ensure the **backend** is running on `http://localhost:9000` (choose either SBT or Gradle to start it).
2. Start the **frontend** on `http://localhost:4321`.
3. Open your browser and navigate to `http://localhost:4321` to view the application.

---

## Building for Production
### Backend
#### With SBT
1. Package the backend with SBT:
   ```bash
   sbt package
   ```
2. The packaged `.jar` file will be located in the `target/scala-<version>` directory.

#### With Gradle
1. Build the backend with Gradle:
   ```bash
   gradle build
   ```
2. The packaged `.jar` file will be located in the `build/libs` directory.

---

### Frontend
1. Build the frontend using **npm**:
   ```bash
   npm run build
   ```
2. The built static files will be located in the `dist` directory.

---

## Additional Notes
- Ensure your **backend** and **frontend** communicate properly by updating API endpoint URLs in the frontend code (e.g., `src/frontend/src/config.js` or similar files).
- Use GitHub Copilot to assist with:
   - Generating boilerplate code for tests.
   - Writing reusable components.
   - Debugging common issues in the backend and frontend code.
- Check the project documentation (if available) for more advanced configurations and deployment instructions.

If you encounter any issues or have questions, feel free to reach out to the maintainers.