package io.cdc.stream.aspect;

import io.cdc.stream.config.RetryConfig;
import jakarta.annotation.PostConstruct;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.hibernate.TransactionException;

import org.springframework.dao.TransientDataAccessException;
import org.springframework.retry.policy.ExceptionClassifierRetryPolicy;
import org.springframework.retry.policy.NeverRetryPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.stereotype.Component;

@Component
public class PersistentRetryPolicy extends ExceptionClassifierRetryPolicy {

	// 40001 - optimistic concurrency or serialization_failure
	// 40P01 - deadlock
	// 08006 - connection issues (error sending data back);"kill -9" failures, socket
	// timeouts
	// 57P01 - broken pool conn (invalidated connections because of node-failure,
	// node-restart, etc.);"kill -15" failures
	// XX000 - RPC failures (could be intermittent)
	private final String SQL_STATE = "^(40001)|(40P01)|(57P01)|(08006)";

	// oom-killer or a process crash in the middle of a tx
	private final String SQL_MSG = "^(connection is closed)|(connection reset by peer)";

	// XX000 shouldn't be re-tried. Retry it only when the state and msg matches
	private final Map<String, String> errorCodes = Map.of("XX000", "schema version mismatch");

	private final org.springframework.retry.RetryPolicy sp;

	private final org.springframework.retry.RetryPolicy np = new NeverRetryPolicy();

	private final Predicate<SQLException> sqlStatePredicate = exception -> Optional.ofNullable(exception.getSQLState())
		.filter(state -> (state.matches(SQL_STATE)
				|| (errorCodes.containsKey(exception.getSQLState()) && (Optional.ofNullable(exception.getMessage())
					.filter(msg -> msg.contains(errorCodes.get(exception.getSQLState())))
					.isPresent()))))
		.isPresent();

	private final Predicate<SQLException> sqlMsgPredicate = exception -> Optional.ofNullable(exception.getMessage())
		.map(String::toLowerCase)
		.filter(msg -> msg.matches(SQL_MSG))
		.isPresent();

	// SQLTransientConnectionException: (08001)|(08003)
	// - intermittent issues because of a backend failure like connection refused
	// - hikari connection time out
	// - 08001, 08003 - connection does not exist (pool connection timeout)
	// TransactionException
	// - begin, commit, rollback failures
	private final Predicate<Throwable> exceptionPredicate = exception -> (exception instanceof SQLRecoverableException
			|| exception instanceof SQLTransientConnectionException || exception instanceof TransientDataAccessException
			|| exception instanceof TransactionException);

	public PersistentRetryPolicy(RetryConfig retryConfig) {
		sp = new SimpleRetryPolicy(retryConfig.getMaxAttempts());
	}

	@PostConstruct
	public void init() {
		this.setExceptionClassifier(cause -> {
			do {
				if (exceptionPredicate.test(cause) || (cause instanceof SQLException exception
						&& (sqlStatePredicate.or(sqlMsgPredicate).test(exception)))) {
					return sp;
				}
				cause = cause.getCause();
			}
			while (cause != null);
			return np;
		});
	}

}
