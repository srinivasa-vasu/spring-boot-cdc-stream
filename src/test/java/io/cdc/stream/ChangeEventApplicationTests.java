package io.cdc.stream;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * Loading the context starts the embedded engine and opens a pool against both the source
 * and the sink, so this needs two live YugabyteDB endpoints and a replication slot. Run
 * it with {@code -Dspring.profiles.active=local} once those exist.
 */
@Disabled("requires a live source and sink; see class javadoc")
@SpringBootTest
class ChangeEventApplicationTests {

	@Test
	void contextLoads() {
	}

}
