package io.cdc.stream.service;

import io.cdc.stream.entity.Products;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService extends PersistentService<Products> {

	private static final String UPSERT = """
			INSERT INTO products (id, category, ean, price, quantity, rating, title, vendor, created_at)
			VALUES (?, ?, ?, ?, ?, ?, ? ,? ,?)
			ON CONFLICT (id)
			DO UPDATE SET
			category = COALESCE(EXCLUDED.category, products.category), ean = COALESCE(EXCLUDED.ean, products.ean),
			price = COALESCE(EXCLUDED.price, products.price), quantity = COALESCE(EXCLUDED.quantity, products.quantity),
			rating = COALESCE(EXCLUDED.rating, products.rating), title = COALESCE(EXCLUDED.title, products.title),
			vendor = COALESCE(EXCLUDED.vendor, products.vendor), created_at = COALESCE(EXCLUDED.created_at, products.created_at)
			""";

	private static final String DELETE = """
			DELETE FROM products WHERE id = ?
			""";

	public ProductService() {
	}

	@Transactional
	public void delete(Products product) {
		delete(DELETE, product);
	}

	@Transactional
	public void save(Products product) {
		save(UPSERT, product);
	}

	@Transactional
	public void save(List<Products> products) {
		save(UPSERT, products);
	}

	@Transactional
	public void delete(List<Products> products) {
		delete(DELETE, products);
	}

	@Override
	public final void prepareWrite(PreparedStatement ps, Products product) throws SQLException {
		ps.setLong(1, product.id());
		ps.setString(2, product.category());
		ps.setString(3, product.ean());
		ps.setBigDecimal(4, product.price());
		ps.setInt(5, product.quantity());
		ps.setBigDecimal(6, product.rating());
		ps.setString(7, product.title());
		ps.setString(8, product.vendor());
		ps.setTimestamp(9, product.createdAt() != null ? Timestamp.from(product.createdAt()) : null);
	}

	@Override
	public final void prepareDelete(PreparedStatement ps, Products product) throws SQLException {
		ps.setLong(1, product.id());
	}

}
