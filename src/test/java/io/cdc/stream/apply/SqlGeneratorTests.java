package io.cdc.stream.apply;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.connect.data.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlGeneratorTests {

	private final SqlGenerator generator = new SqlGenerator();

	@Test
	@DisplayName("a full image upserts every column and updates the non-key ones on conflict")
	void fullImageUpserts() {
		SqlGenerator.Statement statement = generator.upsert(schema(), List.of("id", "quantity", "note"));

		assertThat(statement.sql()).isEqualTo("INSERT INTO \"public\".\"orders\" (\"id\", \"quantity\", \"note\") "
				+ "VALUES (?, ?, ?) ON CONFLICT (\"id\") "
				+ "DO UPDATE SET \"quantity\" = EXCLUDED.\"quantity\", \"note\" = EXCLUDED.\"note\"");
		assertThat(statement.bindColumns()).containsExactly("id", "quantity", "note");
	}

	@Test
	@DisplayName("a partial image updates in place, so it cannot fail on columns it does not carry")
	void partialImageUpdatesInPlace() {
		SqlGenerator.Statement statement = generator.update(schema(), List.of("id", "quantity"));

		assertThat(statement.sql()).isEqualTo("UPDATE \"public\".\"orders\" SET \"quantity\" = ? WHERE \"id\" = ?");
		// Assignments bind first, then the key predicate.
		assertThat(statement.bindColumns()).containsExactly("quantity", "id");
	}

	@Test
	@DisplayName("an explicit NULL is written, not coalesced away")
	void explicitNullIsAssigned() {
		SqlGenerator.Statement statement = generator.update(schema(), List.of("id", "note"));

		// The old hand-written upsert used COALESCE(EXCLUDED.note, orders.note), which
		// made
		// UPDATE ... SET note = NULL impossible to replicate.
		assertThat(statement.sql()).doesNotContain("COALESCE");
		assertThat(statement.sql()).contains("SET \"note\" = ?");
	}

	@Test
	@DisplayName("an event that changes nothing outside the key produces no statement")
	void keyOnlyUpdateIsANoOp() {
		assertThat(generator.update(schema(), List.of("id"))).isNull();
	}

	@Test
	void deleteTargetsTheKey() {
		SqlGenerator.Statement statement = generator.delete(schema());

		assertThat(statement.sql()).isEqualTo("DELETE FROM \"public\".\"orders\" WHERE \"id\" = ?");
		assertThat(statement.bindColumns()).containsExactly("id");
	}

	@Test
	@DisplayName("a table whose columns are all key columns cannot conflict-update")
	void allKeyColumnsDoesNothingOnConflict() {
		TableSchema keyOnly = new TableSchema(new TableId("public", "tags"), columns(Map.of("id", Schema.INT64_SCHEMA)),
				List.of("id"));

		assertThat(generator.upsert(keyOnly, List.of("id")).sql()).endsWith("ON CONFLICT (\"id\") DO NOTHING");
	}

	@Test
	@DisplayName("a jsonb column is cast, since PostgreSQL will not coerce it from text")
	void jsonColumnsAreCast() {
		Map<String, TableSchema.Column> columns = new LinkedHashMap<>();
		columns.put("id", column("id", Schema.INT64_SCHEMA, "bigint"));
		columns.put("payload", column("payload", Schema.STRING_SCHEMA, "jsonb"));
		TableSchema schema = new TableSchema(new TableId("public", "events"), columns, List.of("id"));

		assertThat(generator.upsert(schema, List.of("id", "payload")).sql()).contains("VALUES (?, ?::jsonb)");
	}

	@Test
	@DisplayName("a new column produces a new statement rather than needing a code change")
	void addedColumnFlowsThroughAutomatically() {
		String before = generator.upsert(schema(), List.of("id", "quantity")).sql();
		String after = generator.upsert(schema(), List.of("id", "quantity", "note")).sql();

		assertThat(before).doesNotContain("note");
		assertThat(after).contains("\"note\"");
	}

	// --- fixtures -----------------------------------------------------------

	/** The same table, plus the column a last-writer-wins guard compares. */
	private static TableSchema versioned() {
		Map<String, TableSchema.Column> columns = new LinkedHashMap<>();
		columns.put("id", column("id", Schema.INT64_SCHEMA, "bigint"));
		columns.put("quantity", column("quantity", Schema.INT32_SCHEMA, "integer"));
		columns.put("updated_at", column("updated_at", Schema.INT64_SCHEMA, "timestamptz"));
		return new TableSchema(new TableId("public", "orders"), columns, List.of("id"));
	}

	private static TableSchema schema() {
		Map<String, TableSchema.Column> columns = new LinkedHashMap<>();
		columns.put("id", column("id", Schema.INT64_SCHEMA, "bigint"));
		columns.put("quantity", column("quantity", Schema.INT32_SCHEMA, "integer"));
		columns.put("note", column("note", Schema.STRING_SCHEMA, "text"));
		return new TableSchema(new TableId("public", "orders"), columns, List.of("id"));
	}

	private static Map<String, TableSchema.Column> columns(Map<String, Schema> fields) {
		Map<String, TableSchema.Column> columns = new LinkedHashMap<>();
		fields.forEach((name, schema) -> columns.put(name, column(name, schema, TypeMapper.sqlType(schema))));
		return columns;
	}

	private static TableSchema.Column column(String name, Schema connectSchema, String sqlType) {
		return new TableSchema.Column(name, connectSchema, sqlType, TypeMapper.placeholder(sqlType));
	}


	@Test
	@DisplayName("the conflict guard compares the sink row against the incoming one")
	void upsertGuardComparesAgainstExcluded() {
		SqlGenerator.Statement guarded = generator.upsert(versioned(), List.of("id", "quantity", "updated_at"),
				new SqlGenerator.Guard("updated_at", true));

		// The target row is addressed by the table's own name, which PostgreSQL provides as
		// the implicit alias in ON CONFLICT. No extra bind: the incoming value is already
		// among the inserted columns.
		assertThat(guarded.sql())
			.contains("WHERE \"orders\".\"updated_at\" < EXCLUDED.\"updated_at\"");
		assertThat(guarded.bindColumns()).containsExactly("id", "quantity", "updated_at");
	}

	@Test
	@DisplayName("the replay form relaxes to <= so an applied row can be told from a rejected one")
	void replayGuardIsNonStrict() {
		SqlGenerator.Statement strict = generator.upsert(versioned(), List.of("id", "quantity", "updated_at"),
				new SqlGenerator.Guard("updated_at", true));
		SqlGenerator.Statement replay = generator.upsert(versioned(), List.of("id", "quantity", "updated_at"),
				new SqlGenerator.Guard("updated_at", false));

		assertThat(strict.sql()).contains(" < EXCLUDED.");
		assertThat(replay.sql()).contains(" <= EXCLUDED.");
		// Distinct cache entries, or the second would silently return the first.
		assertThat(replay.sql()).isNotEqualTo(strict.sql());
	}

	@Test
	@DisplayName("a partial update binds the incoming version as an extra parameter")
	void updateGuardBindsTheVersion() {
		SqlGenerator.Statement guarded = generator.update(versioned(), List.of("id", "quantity", "updated_at"),
				new SqlGenerator.Guard("updated_at", true));

		assertThat(guarded.sql()).contains("AND \"updated_at\" < ");
		// Appended last, after the assignments and the key.
		assertThat(guarded.bindColumns()).endsWith("updated_at");
	}

	@Test
	@DisplayName("a delete is never strict: an equal version still deletes")
	void deleteGuardIsNonStrict() {
		SqlGenerator.Statement guarded = generator.delete(versioned(), List.of("id", "updated_at"),
				new SqlGenerator.Guard("updated_at", true));

		// The version in a before image is what the deleter saw. A sink row that has moved
		// on holds changes the deleter did not know about; one that is identical has not.
		assertThat(guarded.sql()).contains("AND \"updated_at\" <= ");
	}

	@Test
	@DisplayName("the guard is dropped when the event does not carry the version column")
	void guardIsDroppedWhenTheColumnIsAbsent() {
		// A partial image under REPLICA IDENTITY CHANGE may not include it, and there is
		// then nothing to compare against.
		SqlGenerator.Statement guarded = generator.upsert(versioned(), List.of("id", "quantity"),
				new SqlGenerator.Guard("updated_at", true));

		assertThat(guarded.sql()).doesNotContain("updated_at");
	}


	@Test
	@DisplayName("an applied row and a tie-rejected row leave the sink in the same state")
	void appliedAndTieRejectedAreIndistinguishableAfterwards() {
		// The reason a guarded statement is executed row by row rather than batched and
		// reconstructed afterwards. Both outcomes leave the sink holding the incoming
		// version; only the row data differs, and the version is all a probe can see.
		// Guarding this as a test so the batching optimisation is not re-attempted.
		SqlGenerator.Statement strict = generator.upsert(versioned(), List.of("id", "quantity", "updated_at"),
				new SqlGenerator.Guard("updated_at", true));

		assertThat(strict.sql()).contains(" < EXCLUDED.");
		// After applying, sink == incoming. After losing a tie, sink == incoming as well.
		// No comparison on that column can separate them.
	}

}
