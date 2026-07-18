package ai.operativus.agentmanager;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Spring Boot default context-loads sanity test, but @SpringBootTest here tries
// localhost:5432 (no Testcontainers). BaseIntegrationTest already covers context
// loading for CI via Testcontainers, so this scaffold test is redundant.
@Disabled("Superseded by BaseIntegrationTest-based context coverage; requires host Postgres. Not CI-compatible.")
@SpringBootTest
@Tag("integration")
class AgentmanagerApplicationTests {

	@Test
	void contextLoads() {
	}

}
