package io.cdc.stream.config;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * Composes replication origin names as {@code <prefix>_<pipeline>_<lane>}.
 *
 * <p>
 * A replication origin can be held by one session at a time, so parallel apply needs one
 * origin per lane. The names are derived rather than generated: lane {@code n} always
 * resolves to the same string, so restarting reuses the origins it already registered.
 *
 * <p>
 * A random suffix per session would also give each lane a private origin, but origins are
 * permanent catalog rows that nothing drops — every restart would leak one, the roident
 * space is 16 bits, and the number simultaneously in use is bounded by
 * {@code max_replication_slots}. A bounded, derived set has none of that: at most
 * {@code concurrency} origins ever exist for a pipeline.
 *
 * <p>
 * The {@code pipeline} component — replication slot, or Kafka consumer group — is what
 * keeps two pipelines writing to the same sink from colliding on lane 1.
 *
 * <p>
 * On the read side a peer should ignore the whole family rather than each name. The
 * dispatcher matches {@code consumer.ignore-origins} entries as prefixes, so configuring
 * {@link #family} once covers every lane, however many there turn out to be.
 */
public final class OriginNames {

	/**
	 * Origin names are interpolated into connection init SQL, which cannot be
	 * parameterised, so every component is restricted to characters that cannot alter the
	 * statement.
	 */
	static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_.\\-]{1,63}");

	private OriginNames() {
	}

	/** The shared prefix of every lane, which is what a peer filters on. */
	public static String family(String prefix, String pipeline) {
		return validate(prefix) + '_' + validate(pipeline);
	}

	/**
	 * @param lane 1-based, matching how the lanes are numbered in logs and in
	 * {@code kafka.concurrency}
	 */
	public static String lane(String prefix, String pipeline, int lane) {
		if (lane < 1) {
			throw new IllegalArgumentException("Origin lane is 1-based; got " + lane);
		}
		String name = family(prefix, pipeline) + '_' + lane;
		if (!SAFE.matcher(name).matches()) {
			throw new IllegalStateException(String.format(
					"Composed replication origin name '%s' must match %s. It is interpolated into the connection "
							+ "init SQL, which cannot be parameterised. Shorten consumer.apply-origin-name or the "
							+ "slot/consumer-group name.",
					name, SAFE.pattern()));
		}
		return name;
	}

	/** Every lane name for the given concurrency, in order. */
	public static List<String> lanes(String prefix, String pipeline, int concurrency) {
		if (concurrency < 1) {
			throw new IllegalArgumentException("Concurrency must be at least 1; got " + concurrency);
		}
		return IntStream.rangeClosed(1, concurrency).mapToObj(lane -> lane(prefix, pipeline, lane)).toList();
	}

	private static String validate(String component) {
		if (component == null || component.isBlank()) {
			throw new IllegalStateException("Replication origin name components must not be blank");
		}
		if (!SAFE.matcher(component).matches()) {
			throw new IllegalStateException(String.format(
					"Replication origin name component '%s' must match %s, because it is interpolated into the "
							+ "connection init SQL and cannot be parameterised.",
					component, SAFE.pattern()));
		}
		return component;
	}

}
