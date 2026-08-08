package io.cdc.stream.apply;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One independent apply path: a pool of exactly one connection, the replication origin that
 * connection holds, and the templates bound to it.
 *
 * <p>
 * The pool is one connection per lane rather than one pool of N for a reason that is easy
 * to miss. The origin is claimed by Hikari's {@code connectionInitSql}, which is a single
 * static string — every connection in a pool would therefore claim the <em>same</em> origin,
 * and only the first could succeed. A lane per origin is the only shape that works.
 *
 * <p>
 * Everything that must land in the row transaction has to go through {@link #jdbcTemplate()}
 * — the watermark and the Kafka offsets included. Using any other template means a different
 * connection and therefore a different transaction, which silently breaks the atomicity the
 * whole design rests on.
 */
public record ApplyLane(int index, String originName, JdbcTemplate jdbcTemplate,
		TransactionTemplate transactionTemplate) {

	@Override
	public String toString() {
		return "lane " + index + (originName == null ? "" : " (origin '" + originName + "')");
	}

}
