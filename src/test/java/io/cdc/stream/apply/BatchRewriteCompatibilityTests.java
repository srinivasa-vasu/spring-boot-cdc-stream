package io.cdc.stream.apply;

import com.yugabyte.core.NativeQuery;
import com.yugabyte.core.Parser;
import java.util.List;
import org.apache.kafka.connect.data.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asks the driver itself whether {@code reWriteBatchedInserts} can apply to the
 * statements this pipeline generates, rather than assuming either way.
 *
 * <p>
 * The rewrite folds a batch of N inserts into a single multi-row {@code INSERT}. That is
 * a large win, but it is only eligible for statements the driver's parser recognises as
 * values-only inserts — so whether our upsert qualifies determines both whether the
 * setting does anything and whether a duplicate key inside one batch could raise
 * {@code 21000}.
 */
class BatchRewriteCompatibilityTests {

	private final SqlGenerator generator = new SqlGenerator();

	@Test
	@DisplayName("the driver decides whether our upsert is rewrite-eligible")
	void upsertRewriteEligibility() throws Exception {
		String upsert = generator.upsert(schema(), List.of("id", "quantity", "note")).sql();

		assertThat(rewritable(upsert)).describedAs("ON CONFLICT upsert eligible for reWriteBatchedInserts: %s", upsert)
			.isEqualTo(REWRITES_ON_CONFLICT);
	}

	@Test
	@DisplayName("a plain insert is rewrite-eligible, confirming the probe works")
	void plainInsertIsEligible() throws Exception {
		assertThat(rewritable("INSERT INTO \"public\".\"orders\" (\"id\") VALUES (?)")).isTrue();
	}

	@Test
	void updateAndDeleteAreNeverRewritten() throws Exception {
		assertThat(rewritable(generator.update(schema(), List.of("id", "quantity")).sql())).isFalse();
		assertThat(rewritable(generator.delete(schema()).sql())).isFalse();
	}

	/**
	 * A guarded upsert must NOT be rewrite-eligible, and its {@code RETURNING} clause is
	 * what makes it so.
	 *
	 * <p>
	 * The rewrite would collapse the batch and report {@code SUCCESS_NO_INFO} instead of
	 * per-row counts, which is the only signal that says which changes the guard refused —
	 * the outcome cannot be reconstructed afterwards, since an applied row and a row that
	 * lost a tie both leave the sink holding the incoming version. Losing this would not
	 * break the apply; it would quietly make the conflict log wrong, which is worse.
	 *
	 * <p>
	 * The second assertion is the interesting one: strip the {@code RETURNING} and the same
	 * statement becomes eligible again. So the guard itself buys no exemption, and a change
	 * that drops the clause as redundant — nothing reads those rows — silently reintroduces
	 * the bug.
	 */
	@Test
	@DisplayName("a guarded upsert opts out of the rewrite, via RETURNING")
	void guardedUpsertOptsOutOfTheRewrite() throws Exception {
		String guarded = generator
			.upsert(schema(), List.of("id", "quantity", "note"), new SqlGenerator.Guard("quantity", true))
			.sql();

		assertThat(guarded).contains(" RETURNING \"id\"");
		assertThat(rewritable(guarded)).describedAs("guarded upsert must not be rewritten: %s", guarded).isFalse();

		String withoutReturning = guarded.substring(0, guarded.indexOf(" RETURNING "));
		assertThat(rewritable(withoutReturning))
			.describedAs("RETURNING, not the guard, is what defeats the rewrite: %s", withoutReturning)
			.isEqualTo(REWRITES_ON_CONFLICT);
	}

	/**
	 * The bundled driver <em>does</em> rewrite {@code INSERT ... ON CONFLICT DO UPDATE}
	 * into a single multi-row statement. Two consequences, and this test exists to pin
	 * both:
	 * <ul>
	 * <li>{@code reWriteBatchedInserts=true} is worth enabling — it applies to our main
	 * write path, not just to plain inserts.
	 * <li>Cutting a run on a repeated key in {@code ChangeEventApplier.applyRuns} is
	 * <b>load-bearing, not defensive</b>. One rewritten command cannot touch the same row
	 * twice; PostgreSQL raises {@code 21000} instead of applying the changes in order.
	 * </ul>
	 * If a driver upgrade flips this, the assertion fails and the pairing needs
	 * revisiting.
	 */
	private static final boolean REWRITES_ON_CONFLICT = true;

	/**
	 * The general rule the guarded upsert relies on, pinned independently of our own SQL:
	 * {@code RETURNING} makes any insert ineligible, because the driver must deliver
	 * per-statement results and so cannot fold the batch into one command.
	 *
	 * <p>
	 * Batching a statement that returns rows is safe under {@code executeBatch} —
	 * {@code BatchResultHandler.handleResultRows} discards them unless generated keys were
	 * requested — but NOT under {@code executeUpdate}, which calls
	 * {@code checkNoResultUpdate} and throws. That asymmetry is why
	 * {@code ChangeEventApplier} batches even a single row.
	 */
	@Test
	@DisplayName("RETURNING makes a plain insert ineligible for the rewrite")
	void returningDefeatsTheRewrite() throws Exception {
		assertThat(rewritable("INSERT INTO \"public\".\"orders\" (\"id\") VALUES (?)")).isTrue();
		assertThat(rewritable("INSERT INTO \"public\".\"orders\" (\"id\") VALUES (?) RETURNING \"id\"")).isFalse();
	}

	private static boolean rewritable(String sql) throws Exception {
		List<NativeQuery> queries = Parser.parseJdbcSql(sql, true, true, false, true, true);
		return queries.size() == 1 && queries.get(0).getCommand().isBatchedReWriteCompatible();
	}

	private static TableSchema schema() {
		java.util.Map<String, TableSchema.Column> columns = new java.util.LinkedHashMap<>();
		columns.put("id", column("id", Schema.INT64_SCHEMA, "bigint"));
		columns.put("quantity", column("quantity", Schema.INT32_SCHEMA, "integer"));
		columns.put("note", column("note", Schema.STRING_SCHEMA, "text"));
		return new TableSchema(new TableId("public", "orders"), columns, List.of("id"));
	}

	private static TableSchema.Column column(String name, Schema connectSchema, String sqlType) {
		return new TableSchema.Column(name, connectSchema, sqlType, TypeMapper.placeholder(sqlType));
	}

}
