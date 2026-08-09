package io.cdc.stream.apply;

import io.cdc.stream.event.OPERATION;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.connect.data.Schema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A source column the sink does not have is harmless while its values are null and is data
 * loss the moment one is not. That is a property of the value, so it has to be decided per
 * row — the schema-level check never sees one.
 */
class ColumnProjectionTests {

	@Test
	void aRowThatFitsIsReturnedUnchanged() {
		ChangeRow row = row(Map.of("id", 1, "name", "ada"));

		// Same instance, not just equal: the common case must not allocate.
		assertThat(ColumnProjection.project(row, Set.of("id", "name"))).isSameAs(row);
	}

	@Test
	void aWiderSinkIsStillUnchanged() {
		ChangeRow row = row(Map.of("id", 1));

		assertThat(ColumnProjection.project(row, Set.of("id", "name", "created_at"))).isSameAs(row);
	}

	@Test
	void anUnknownColumnWithANullValueIsDropped() {
		// The case this exists for: source gains a column, the sink migration lags, and
		// nothing is actually being lost yet.
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("id", 1);
		values.put("name", "ada");
		values.put("nickname", null);

		ChangeRow projected = ColumnProjection.project(row(values), Set.of("id", "name"));

		assertThat(projected.values()).containsExactly(Map.entry("id", 1), Map.entry("name", "ada"));
	}

	@Test
	void anUnknownColumnWithAValueIsFatal() {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("id", 1);
		values.put("nickname", "countess");

		assertThatThrownBy(() -> ColumnProjection.project(row(values), Set.of("id")))
			.isInstanceOf(UnrecoverableApplyException.class)
			.hasMessageContaining("nickname")
			.hasMessageContaining("would lose data");
	}

	@Test
	void aMissingKeyColumnIsFatalEvenWhenNull() {
		// Nothing to target the row with, so nullness is beside the point.
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("id", null);
		values.put("name", "ada");

		assertThatThrownBy(() -> ColumnProjection.project(row(values), Set.of("name")))
			.isInstanceOf(UnrecoverableApplyException.class)
			.hasMessageContaining("primary key");
	}

	@Test
	void unknownSinkColumnsLeaveTheRowAlone() {
		// A truncate carries no schema, so the sink's columns were never read.
		ChangeRow row = row(Map.of("id", 1));

		assertThat(ColumnProjection.project(row, null)).isSameAs(row);
	}

	@Test
	void projectionPreservesEverythingElseAboutTheRow() {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("id", 7);
		values.put("gone", null);

		ChangeRow projected = ColumnProjection.project(row(values), Set.of("id"));

		assertThat(projected.op()).isEqualTo(OPERATION.c);
		assertThat(projected.txId()).isEqualTo("tx1");
		assertThat(projected.lsn()).isEqualTo(42L);
		assertThat(projected.fullImage()).isTrue();
	}

	private static ChangeRow row(Map<String, Object> values) {
		Map<String, TableSchema.Column> columns = new LinkedHashMap<>();
		values.keySet()
			.forEach(name -> columns.put(name, new TableSchema.Column(name, Schema.OPTIONAL_STRING_SCHEMA, "text", "?")));
		TableSchema schema = new TableSchema(new TableId("public", "users"), columns, List.of("id"));
		return new ChangeRow(schema, OPERATION.c, values, "tx1", 42L, null, true);
	}

}
