# Contributing to JavaDropbox

Read this before starting any change — human or agent. It's short on purpose.

## 1. Style is enforced, not requested

Backend code follows the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html), mechanically enforced via [Spotless](https://github.com/diffplug/spotless) + `google-java-format`. `./gradlew build` fails if a file you touched isn't formatted correctly.

```bash
./gradlew spotlessApply   # auto-format whatever you changed
./gradlew spotlessCheck   # just check, no changes (also runs as part of `build`)
```

Spotless only checks files that differ from `main` (see `ratchetFrom` in `build.gradle`) — you will never be asked to reformat code you didn't touch, and you should never do so unprompted either.

Frontend code follows the existing `frontend/eslint.config.js` rules:

```bash
cd frontend && npm run lint
```

If either check fails, fix it before considering the work done — don't hand back code that only passes because a formatter wasn't run.

## 2. Follow the pattern that's already there, not the oldest one

This codebase has one legacy inconsistency: `WebController.java` uses field-level `@Autowired` injection. Every other controller and service (`ShareController`, `FileVersionController`, `ShareTokenService`, `AuthService`, etc.) uses constructor injection. **New code uses constructor injection.** Don't copy `WebController`'s style just because it's nearby — it predates the rest of the codebase's convention, not the other way around.

More generally: before adding a new pattern, check whether one already exists for the same kind of problem (a new service, a new exception type, a new modal component) and match it. Consistency with the rest of the codebase beats a "better" pattern imported from elsewhere.

## 3. Every change needs a real test, and the full suite must pass

- New backend behavior gets an integration test in `src/test/java/com/javadropbox/javadropbox/`, following the existing style (see `ShareLinkIntegrationTests.java` for the most recent example — `@SpringBootTest`, `@AutoConfigureMockMvc`, real HTTP assertions via `MockMvc`, not unit tests that mock everything).
- Before calling anything done: `./gradlew build` (backend) and, once it exists, the frontend test/lint job — both must be green. Don't rely on "it looked right in the browser."
- Tests should prove the specific thing that was asked for — auth required where it should be, a boundary condition rejected, a real HTTP round trip — not just that a function got called.

## 4. Security checklist for anything touching files or auth

- Any new code that reads/writes files by a user-supplied path must validate it the same way `FileServingService.validatePathSecurity` already does — resolve, normalize, and confirm the result stays inside the serving directory. Don't build a second, slightly different path-safety check.
- Any new public (`permitAll`) route in `SecurityConfig` needs a one-line comment saying *why* it's public. Public-by-accident is how path traversal and auth bypass bugs get shipped.
- Never hardcode a real secret. Follow the existing pattern in `application.properties` (`app.share.jwt-secret`) — a clearly-labeled dev-only default, overridable via an env var, documented as such.
- If a change affects who can see, download, delete, or share a file, add a test for the negative case (the user who should be *denied* access), not just the happy path.

## 5. Scope discipline

Do the ticket. Don't reformat unrelated files, don't refactor something you noticed in passing, don't add a feature flag or abstraction for a hypothetical future need. If you notice something else worth fixing, say so at the end of your summary instead of fixing it inline — it's easier to review one focused change than to find the real diff inside a drive-by cleanup.

## 6. Commit messages

This repo's convention is short and plain, not a template:

- Either a plain lowercase sentence describing what changed (`added a share icon to the file table next to download/delete`), or `closes #N` when the commit fully resolves a tracked issue and the issue body carries the detail.
- No AI/tool co-author trailers.
- One logical change per commit — if you're doing two unrelated things, that's two commits.

## 7. Docs

If a change affects how someone runs the app, what an API endpoint does, or what's in the tech stack, update the relevant section of `README.md` (or `frontend/README.md` for frontend-only setup) in the same change — not as a follow-up someone else has to remember to do.
