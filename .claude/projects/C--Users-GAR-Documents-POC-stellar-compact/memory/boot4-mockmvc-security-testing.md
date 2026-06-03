---
name: boot4-mockmvc-security-testing
description: Spring Boot 4 dropped @WebMvcTest and @AutoConfigureMockMvc — how to test MVC + security
metadata:
  type: reference
---

**Spring Boot 4.0.6 removed `@WebMvcTest` AND `@AutoConfigureMockMvc`** (the package
`org.springframework.boot.test.autoconfigure.web.servlet` no longer ships `AutoConfigureMockMvc`).

**How to apply:**
- Plain controller unit tests: standalone `MockMvcBuilders.standaloneSetup(controller).build()`
  (this repo's `MatchControllerTest`/`FactionConfigControllerTest` already do this — no Spring
  context, so they're immune to security auto-config).
- To exercise the **real Spring Security filter chain**: `@SpringBootTest(classes = SomeTestApp)`
  + build MockMvc from the web context and apply the security configurer:
  ```java
  mvc = MockMvcBuilders.webAppContextSetup(context)
          .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
          .build();
  ```
  Needs `spring-security-test` (test scope). Example: `AuthFlowIntegrationTest` /
  `AuthTestApp` in the api module.

**Gotchas:**
- A slim `@EnableAutoConfiguration` test app with the security starter on the classpath gets
  Boot's default lock-everything chain unless you `@Import` the project's `SecurityConfig`
  (did this for `LiveStreamTestApp` so `/ws` stays open + the `JwtDecoder` bean exists).
- The slim context has **no `ObjectMapper` bean** to autowire — just `new ObjectMapper()` in
  the test.

Related: [[dev-jwt-auth]], [[backend-jdk25-build]].
