package io.cdc.stream.apply;

import io.cdc.stream.event.OPERATION;
import io.cdc.stream.event.PipelineEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.connect.data.Schema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The commit boundary used by {@code transaction-scope=table}, which is the only scope
 * available on per-table Kafka topics. It has no BEGIN/END markers to work from, so a
 * change of transaction or of table is the entire signal.
 */
class ChangeBufferTests {

	@Test
	void anEmptyBufferHasNoBoundaryInFrontOfIt() {
		ChangeBuffer buffer = new ChangeBuffer();

		assertThat(buffer.crossesBoundary(row("orders", "tx1"))).isFalse();
	}

	@Test
	void sameTransactionAndTableStaysInOneUnit() {
		ChangeBuffer buffer = new ChangeBuffer();
		buffer.addRow(row("orders", "tx1"));

		assertThat(buffer.crossesBoundary(row("orders", "tx1"))).isFalse();
	}

	@Test
	void aNewTransactionEndsTheUnit() {
		ChangeBuffer buffer = new ChangeBuffer();
		buffer.addRow(row("orders", "tx1"));

		assertThat(buffer.crossesBoundary(row("orders", "tx2"))).isTrue();
	}

	@Test
	void aDifferentTableEndsTheUnitEvenWithinOneTransaction() {
		ChangeBuffer buffer = new ChangeBuffer();
		buffer.addRow(row("orders", "tx1"));

		// This is what makes the scope per-table: a transaction spanning two tables
		// becomes two sink transactions rather than one that spans partitions.
		assertThat(buffer.crossesBoundary(row("products", "tx1"))).isTrue();
	}

	@Test
	void missingTransactionMetadataIsNotTreatedAsABoundary() {
		ChangeBuffer buffer = new ChangeBuffer();
		buffer.addRow(row("orders", null));

		// The snapshot emits no transaction ids. Reading null as "different" would commit
		// every snapshot row in its own transaction.
		assertThat(buffer.crossesBoundary(row("orders", null))).isFalse();
		assertThat(buffer.crossesBoundary(row("orders", "tx1"))).isFalse();
	}

	@Test
	void positionsKeepTheHighestOffsetPerPartition() {
		ChangeBuffer buffer = new ChangeBuffer();
		buffer.addEvent(event("ybdb.all", 0, 10));
		buffer.addEvent(event("ybdb.all", 0, 12));
		buffer.addEvent(event("ybdb.all", 1, 3));
		buffer.addEvent(event("ybdb.all", 0, 11));

		assertThat(buffer.positions()).containsExactlyInAnyOrder(new PartitionPosition("ybdb.all", 0, 12),
				new PartitionPosition("ybdb.all", 1, 3));
	}

	@Test
	void resetClearsPositionsAndBoundaryState() {
		ChangeBuffer buffer = new ChangeBuffer();
		buffer.addEvent(event("ybdb.all", 0, 10));
		buffer.addRow(row("orders", "tx1"));

		buffer.reset();

		assertThat(buffer.positions()).isEmpty();
		assertThat(buffer.rowCount()).isZero();
		// Boundary state is gone with it, so the next row starts a fresh unit.
		assertThat(buffer.crossesBoundary(row("products", "tx9"))).isFalse();
	}

	private static PipelineEvent event(String topic, int partition, long offset) {
		return new PipelineEvent(null, new PartitionPosition(topic, partition, offset));
	}

	private static ChangeRow row(String table, String txId) {
		Map<String, TableSchema.Column> columns = new LinkedHashMap<>();
		columns.put("id", new TableSchema.Column("id", Schema.INT32_SCHEMA, "integer", "?"));
		TableSchema schema = new TableSchema(new TableId("public", table), columns, List.of("id"));
		return new ChangeRow(schema, OPERATION.c, Map.of("id", 1), txId, 1L, null, true);
	}

}
