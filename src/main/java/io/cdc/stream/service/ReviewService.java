package io.cdc.stream.service;

import io.cdc.stream.entity.Reviews;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewService extends PersistentService<Reviews> {

	private static final String UPSERT = """
			INSERT INTO reviews (id, reviewer, product_id, rating, body, created_at)
			VALUES (?, ?, ?, ?, ?, ?)
			ON CONFLICT (id)
			DO UPDATE SET
			reviewer = COALESCE(EXCLUDED.reviewer, reviews.reviewer), product_id = COALESCE(EXCLUDED.product_id, reviews.product_id),
			rating = COALESCE(EXCLUDED.rating, reviews.rating), body = COALESCE(EXCLUDED.body, reviews.body),
			created_at = COALESCE(EXCLUDED.created_at, reviews.created_at)
			""";

	private static final String DELETE = """
			DELETE FROM reviews WHERE id = ?
			""";

	public ReviewService() {
	}

	@Transactional
	public void delete(Reviews review) {
		delete(DELETE, review);
	}

	@Transactional
	public void save(Reviews review) {
		save(UPSERT, review);
	}

	@Transactional
	public void save(List<Reviews> reviews) {
		save(UPSERT, reviews);
	}

	@Transactional
	public void delete(List<Reviews> reviews) {
		delete(DELETE, reviews);
	}

	@Override
	public final void prepareWrite(PreparedStatement ps, Reviews review) throws SQLException {
		ps.setLong(1, review.id());
		ps.setString(2, review.reviewer());
		ps.setObject(3, review.productId(), Types.BIGINT);
		ps.setBigDecimal(4, review.rating());
		ps.setString(5, review.body());
		ps.setTimestamp(6, review.createdAt() != null ? Timestamp.from(review.createdAt()) : null);
	}

	@Override
	public final void prepareDelete(PreparedStatement ps, Reviews review) throws SQLException {
		ps.setLong(1, review.id());
	}

}
