package io.cdc.stream.service;

import io.cdc.stream.entity.Base;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;

@Service
public abstract class PersistentService<T extends Base> {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	protected PersistentService() {
	}

	public final void delete(final String query, final T entity) {
		jdbcTemplate.update(query, ps -> {
			prepareDelete(ps, entity);
		});
	}

	public final void delete(final String query, final List<T> entityList) {
		jdbcTemplate.batchUpdate(query, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(@NonNull PreparedStatement ps, int i) throws SQLException {
				T entity = entityList.get(i);
				prepareDelete(ps, entity);
			}

			@Override
			public int getBatchSize() {
				return entityList.size();
			}
		});
	}

	public final void save(final String query, final T entity) {
		jdbcTemplate.update(query, ps -> {
			prepareWrite(ps, entity);
		});
	}

	public final void save(final String query, final List<T> entityList) {
		jdbcTemplate.batchUpdate(query, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(@NonNull PreparedStatement ps, int i) throws SQLException {
				T entity = entityList.get(i);
				prepareWrite(ps, entity);
			}

			@Override
			public int getBatchSize() {
				return entityList.size();
			}
		});
	}

	public abstract void delete(T entity);

	public abstract void save(T entity);

	public abstract void save(List<T> objects);

	public abstract void delete(List<T> objects);

	protected abstract void prepareWrite(PreparedStatement ps, T entity) throws SQLException;

	protected abstract void prepareDelete(PreparedStatement ps, T entity) throws SQLException;

}
