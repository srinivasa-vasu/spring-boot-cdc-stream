package io.cdc.stream.aspect;

import io.cdc.stream.config.RetryConfig;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.jspecify.annotations.NonNull;

import org.springframework.core.retry.RetryPolicy;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

@Component
public class YBRetryPolicy implements RetryPolicy {

	@lombok.Getter
	private final BackOff backOff;

	public YBRetryPolicy(RetryConfig config) {
		ExponentialBackOff backOff = new ExponentialBackOff();
		backOff.setInitialInterval(config.getInitialInterval());
		backOff.setMultiplier(config.getMultiplier());
		backOff.setMaxInterval(config.getMaxInterval());
		backOff.setMaxAttempts(config.getMaxAttempts());
		backOff.setJitter(config.getJitter());
		this.backOff = backOff;
	}

	// 40001 - optimistic concurrency or serialization_failure
	// 40P01 - deadlock
	// 08006 - connection issues (error sending data back);"kill -9" failures, socket timeouts
	// 57P01 - broken pool conn (invalidated connections because of node-failure, node-restart, etc.);"kill -15" failures
	private final String SQL_STATE = "^(40001)|(40P01)|(57P01)|(08006)";

	// oom-killer or a process crash in the middle of a tx
	private final String SQL_MSG = "^(connection is closed)|(connection reset by peer)";

	// XX000 shouldn't be re-tried. Retry it only when the state and msg matches
	private final Map<String, List<String>> specialCodes =
			Map.of("XX000", List.of("schema version mismatch", "duplicate request"));

	private final Predicate<SQLException> sqlStatePredicate =
			exception -> {
				String state = exception.getSQLState();
				if (state == null) return false;

				// Case 1: direct SQL_STATE match
				if (state.matches(SQL_STATE)) return true;

				// Case 2: special codes check
				if (specialCodes.containsKey(state)) {
					String msg = exception.getMessage();
					if (msg != null) {
						// shouldn't retry commit statements
						return specialCodes.get(state).stream()
								.anyMatch(code -> msg.toLowerCase().contains(code));
					}
				}

				// Default: no match
				return false;
			};

	private final Predicate<SQLException> sqlMsgPredicate =
			exception ->
					Optional.ofNullable(exception.getMessage())
							.filter(msg -> msg.toLowerCase().matches(SQL_MSG))
							.isPresent();

	// SQLTransientConnectionException: (08001)|(08003)
	// - intermittent issues because of a backend failure like connection refused
	// - hikari connection time out
	// - 08001, 08003 - connection does not exist (pool connection timeout)
	// TransactionException
	// - begin, commit, rollback failures
	private final Predicate<Throwable> exceptionPredicate =
			exception ->
					(exception instanceof SQLRecoverableException
							|| exception instanceof SQLTransientConnectionException
							|| exception instanceof TransientDataAccessException
							|| exception instanceof TransactionException);

	@Override
	public boolean shouldRetry(@NonNull Throwable cause) {
		do {
			if (exceptionPredicate.test(cause)
					|| (cause instanceof SQLException exception
					&& (sqlStatePredicate.or(sqlMsgPredicate).test(exception)))) {
				return true;
			}
			cause = cause.getCause();
		}
		while (cause != null);
		return false;
	}
}