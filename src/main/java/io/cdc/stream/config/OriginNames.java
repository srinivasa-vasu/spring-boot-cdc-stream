package io.cdc.stream.config;

import java.util.regex.Pattern;

/**
 * Composes replication origin names as {@code <prefix>_<slot>_<lane>}.
 *
 * <p>
 * {@code consumer.apply-origin-name} is a prefix, not the origin itself. Taken literally it
 * would be a constant, and two pipelines reading different replication slots but writing to
 * the same sink would both try to claim it — the second failing with {@code 55006
 * object_in_use} from inside Hikari's connection-init SQL, which surfaces as an
 * uninformative pool-fill failure. Even against separate sinks a shared name is ambiguous:
 * a peer sees the same {@code source.origin} for both and cannot forward one pipeline's
 * changes while filtering the other's.
 *
 * <p>
 * YugabyteDB's documentation prescribes exactly this shape — the origin name is free-form
 * text that "should be used in a way that makes conflicts between replication origins
 * created by different replication solutions unlikely (for example, by prefixing the
 * replication solution's name to it)".
 *
 * <p>
 * The <b>slot</b> is the discriminator because a replication slot can be consumed by only
 * one connection, so it identifies a pipeline outright rather than by convention. The cost
 * is that slot names are not especially stable here — recreating a slot is a documented
 * operation, since replica identity is fixed at slot creation — and a rename mints a new
 * origin, orphaning the old catalog row. That is survivable only because
 * {@code consumer.ignore-origins} matches <em>prefixes</em>: a peer names the family once
 * and keeps working across renames.
 *
 * <p>
 * The <b>lane</b> component is fixed at 1 today. It exists so that adding parallel apply
 * later adds lanes 2..n beside this one rather than renaming an origin already in use by a
 * running deployment.
 */
public final class OriginNames {

	/**
	 * Origin names are interpolated into connection init SQL, which cannot be
	 * parameterised, so every component is restricted to characters that cannot alter the
	 * statement. This is the only constraint the database imposes: the name is free-form
	 * text in a {@code text} column, with no {@code NAMEDATALEN} limit to respect.
	 */
	static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_.\\-]+");

	/**
	 * Per-component length. Self-imposed — a prefix or slot name longer than this is a
	 * naming problem worth surfacing, not something the database objects to. Capping each
	 * component also bounds the composed name, so it needs no separate limit.
	 */
	static final int MAX_COMPONENT = 63;

	/** The only lane until parallel apply lands. See the class javadoc. */
	public static final int SOLE_LANE = 1;

	private OriginNames() {
	}

	/**
	 * The shared prefix of every lane, and what a peer should name in
	 * {@code consumer.ignore-origins}.
	 */
	public static String family(String prefix, String slot) {
		return validate(prefix, "consumer.apply-origin-name") + '_' + validate(slot, "producer.replication-slot");
	}

	/** @param lane 1-based */
	public static String lane(String prefix, String slot, int lane) {
		if (lane < 1) {
			throw new IllegalArgumentException("Origin lane is 1-based; got " + lane);
		}
		return family(prefix, slot) + '_' + lane;
	}

	private static String validate(String component, String property) {
		if (component == null || component.isBlank()) {
			throw new IllegalStateException(property + " must be set to compose a replication origin name");
		}
		if (!SAFE.matcher(component).matches()) {
			throw new IllegalStateException(String.format(
					"%s is '%s', which must match %s: it becomes part of a replication origin name that is "
							+ "interpolated into the connection init SQL and cannot be parameterised.",
					property, component, SAFE.pattern()));
		}
		if (component.length() > MAX_COMPONENT) {
			throw new IllegalStateException(String.format("%s is %d characters, over the %d this pipeline allows.",
					property, component.length(), MAX_COMPONENT));
		}
		return component;
	}

}
