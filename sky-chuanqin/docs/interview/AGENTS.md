# Repository Guidelines

## use Chinese when you reply to me

## Project Structure & Module Organization
- Maven multi-module (Java 11): `sky-common` holds shared DTOs, result wrappers, and utilities; `sky-server` is the Spring Boot REST API (controllers/services/mappers/security/tasks/config in `src/main/java/com/kira/server`); `mqtt-server` handles MQTT ingestion/bridging.
- Configuration lives in each module under `src/main/resources` (mainly `sky-server/src/main/resources/application.yml`). Docs and notes sit in `sky-server/src/main/java/com/kira/server/md`.
- Build outputs land in each module's `target/` directory. Place new tests under the matching module's `src/test/java`.

## Build, Test, and Development Commands
- `mvn clean verify` (root): builds all modules and runs tests. Add `-DskipTests` to speed up CI if needed.
- `mvn clean package -pl sky-server -am -DskipTests`: builds the API jar at `sky-server/target/sky-server.jar`.
- `mvn spring-boot:run -pl sky-server -Dspring-boot.run.profiles=local`: run the API locally; ensure MySQL/Redis values are set in your profile.
- `mvn spring-boot:run -pl mqtt-server -Dspring-boot.run.profiles=local`: start the MQTT bridge.
- `mvn test -pl sky-server`: run module tests only (create proper fixtures or profile overrides before enabling DB-backed tests).

## Coding Style & Naming Conventions
- Java: 4-space indent, braces on the same line, avoid wildcard imports, prefer Lombok for boilerplate. Keep controller responses wrapped in `Result<>` from `sky-common`.
- Naming: controllers end with `Controller`, interfaces with `I*Service`, implementations `*ServiceImpl`; DTO/VO/Entity/Mapper suffixes follow existing package layout. REST paths use lower-kebab segments (e.g., `/alerts`, `/location`).
- Security and JWT settings live in `config` and `security` packages; update configs alongside code changes.

## Testing Guidelines
- Spring Boot starter test stack (JUnit 5/MockMvc) is available; create tests under `src/test/java` with `*Test` suffixes. Keep integration tests profile-isolated and seed DB/Redis data via test resources or mocks.
- Current coverage is light (e.g., `task/PollutionDetectionTest.java` lives in main); prefer moving new checks into test sources and asserting service/controller paths.

## Commit & Pull Request Guidelines
- Git history favors concise, present-tense summaries; mirror that style and keep one focused change per commit (e.g., "introduce spring security", "split data collection logic").
- PRs: include a brief problem/solution summary, linked issue or ticket, key commands run (`mvn test`/`mvn clean verify`), config or schema changes, and screenshots for UI/API doc adjustments (Knife4j at `/doc.html`).
- Note any operational impacts (new ports, queues, cron/XXL-Job entries) and required credentials; avoid committing secrets--use env vars or profile-specific yml ignored by Git.
