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

}
