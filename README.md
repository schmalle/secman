DRAFT DRAFT DRAFT DRAFT

---

![landing.png](docs/landing.png)

**A requirement management, risk assessment management tool**

---

## Overview / background

secman was initially started as a security requirement formatter tool. The goal was to generate a beautiful looking MS word document out of a well formatted MS Excel sheet (it was just a helper tool for a repeating task).

Then the idea was born to extend this tooling for some other use cases like risk management or context specific document rendering.

---

## Features

- **Requirements Management:** Create, edit, prioritize, and track requirements.
- **Risk Assessment:** Identify, evaluate, and document risks associated with requirements or processes.
- **User Roles:** Assign and manage different user roles (normaluser, adminuser) with appropriate permissions.
- **Automated Analysis:** Integrated tools and scripts for dependency analysis and risk reporting.
- MCP server
- E-Mail notification
- Automatic translation of requirements

## Getting Started

### Prerequisites

- Java (tested with version 21)
- Node.js (tested with version 24)
- MariaDB
- sbt (Scala Build Tool)
- Python (for optional scripts)
- OpenRouter Key (optionally)

### Installation

1. **Clone the repository:**

   ```
   git clone https://github.com/schmalle/secman.git
   cd secman
   ```
2. **Create database**

```cd
cd scritps
./install.sh
```


3. **Build the project (backend):**

```sh
sbt run dev
```

Please note: The play framework ensures that all tables are existing, which are neeeded.


1. **Build the project (frontend):**

   ```sh
   npm run dev
   ```

## Usage

- **Access the UI:** [URL or command to access the user interface]
- **Add requirements:** [Brief instructions]
- **Perform risk assessment:** [Brief instructions]
- **Generate reports:** [Brief instructions]

---

## User Roles

- **normaluser:** Basic access for submitting and tracking requirements/risks.
- **adminuser:** Full administrative rights, including user management and configuration.

---

## Testing

secman includes comprehensive test suites for different components:

### Backend Testing

```sh
# Run all backend tests
cd src/backend
sbt test

# Run specific service tests
sbt "testOnly services.EmailServiceTest"

# Run tests with command line parameters
sbt "testOnly services.EmailServiceTest" \
  -Dtest.email.recipient=test@example.com \
  -Dtest.email.subject="Test Subject"
```

### Open taks

- **[End-to-End Testing Plan](docs/END_TO_END_TEST_PLAN.md)** - Overall testing strategy

### Test Documentation

- **[Email Testing Guide](docs/EMAIL_TESTING_GUIDE.md)** - Comprehensive testing for email functionality
- **[Translation Testing Guide](docs/TRANSLATION_TESTING_GUIDE.md)** - Testing translation features
- **[End-to-End Testing Plan](docs/tasks.md)** - Overall open testing point

---

## Contributing

We welcome contributions at a later stage. For the moment any idea / potential topic would be great.

---

## License

A-GPL 3.0 license

---

## Contact

- **Maintainer:** Markus "flake" Schmall
- Mastodon: flakedev@infosec.exchange
- Telegram: flakedev
- **Email:** markus@schmall.io
