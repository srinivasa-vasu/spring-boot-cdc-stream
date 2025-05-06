package io.cdc.stream.service;

import io.cdc.stream.entity.Orders;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService extends PersistentService<Orders> {

	private static final String UPSERT = """
			INSERT INTO orders (id, user_id, product_id, quantity, discount, subtotal, tax, total, created_at)
			VALUES (?, ?, ?, ?, ?, ?, ? ,? ,?)
			ON CONFLICT (id)
			DO UPDATE SET
			user_id = COALESCE(EXCLUDED.user_id, orders.user_id), product_id = COALESCE(EXCLUDED.product_id, orders.product_id),
			quantity = COALESCE(EXCLUDED.quantity, orders.quantity), discount = COALESCE(EXCLUDED.discount, orders.discount),
			subtotal = COALESCE(EXCLUDED.subtotal, orders.subtotal), tax = COALESCE(EXCLUDED.tax, orders.tax),
			total = COALESCE(EXCLUDED.total, orders.total), created_at = COALESCE(EXCLUDED.created_at, orders.created_at)
			""";

	private static final String DELETE = """
			DELETE FROM orders WHERE id = ?
			""";

	public OrderService() {
	}

	@Transactional
	public void delete(Orders order) {
		delete(DELETE, order);
	}

	@Transactional
	public void save(Orders order) {
		save(UPSERT, order);
	}

	@Transactional
	public void save(List<Orders> orders) {
		save(UPSERT, orders);
	}

	@Transactional
	public void delete(List<Orders> orders) {
		delete(DELETE, orders);
	}

	@Override
	public final void prepareWrite(PreparedStatement ps, Orders order) throws SQLException {
		ps.setLong(1, order.id());
		ps.setObject(2, order.userId(), Types.BIGINT);
		ps.setObject(3, order.productId(), Types.BIGINT);
		ps.setInt(4, order.quantity());
		ps.setBigDecimal(5, order.discount());
		ps.setBigDecimal(6, order.subtotal());
		ps.setBigDecimal(7, order.tax());
		ps.setBigDecimal(8, order.total());
		ps.setTimestamp(9, order.createdAt() != null ? Timestamp.from(order.createdAt()) : null);
	}

	@Override
	public final void prepareDelete(PreparedStatement ps, Orders order) throws SQLException {
		ps.setLong(1, order.id());
	}

}
