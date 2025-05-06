package io.cdc.stream.service;

import io.cdc.stream.entity.Users;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService extends PersistentService<Users> {

	private static final String UPSERT = """
			INSERT  INTO users (id, name, email, address, city, state, zip, birth_date, latitude, longitude, password, source, created_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT (id) DO UPDATE
			SET name = COALESCE(EXCLUDED.name, users.name), email = COALESCE(EXCLUDED.email, users.email),
			address = COALESCE(EXCLUDED.address, users.address), city = COALESCE(EXCLUDED.city, users.city),
			state = COALESCE(EXCLUDED.state, users.state), zip = COALESCE(EXCLUDED.zip, users.zip),
			birth_date = COALESCE(EXCLUDED.birth_date, users.birth_date), latitude = COALESCE(EXCLUDED.latitude, users.latitude),
			longitude = COALESCE(EXCLUDED.longitude, users.longitude), password = COALESCE(EXCLUDED.password, users.password),
			source = COALESCE(EXCLUDED.source, users.source), created_at = COALESCE(EXCLUDED.created_at, users.created_at)
			""";

	private static final String DELETE = """
			DELETE FROM users WHERE id = ?
			""";

	public UserService() {
	}

	@Transactional
	public void delete(Users user) {
		delete(DELETE, user);
	}

	@Transactional
	public void save(Users user) {
		save(UPSERT, user);
	}

	@Transactional
	@Override
	public void save(final List<Users> users) {
		save(UPSERT, users);
	}

	@Transactional
	public void delete(final List<Users> users) {
		delete(DELETE, users);
	}

	@Override
	public final void prepareWrite(PreparedStatement ps, Users user) throws SQLException {
		ps.setLong(1, user.id());
		ps.setString(2, user.name());
		ps.setString(3, user.email());
		ps.setString(4, user.address());
		ps.setString(5, user.city());
		ps.setString(6, user.state());
		ps.setString(7, user.zip());
		ps.setString(8, user.birthDate());
		ps.setBigDecimal(9, user.latitude());
		ps.setBigDecimal(10, user.longitude());
		ps.setString(11, user.password());
		ps.setString(12, user.source());
		ps.setTimestamp(13, user.createdAt() != null ? Timestamp.from(user.createdAt()) : null);
	}

	@Override
	public final void prepareDelete(PreparedStatement ps, Users user) throws SQLException {
		ps.setLong(1, user.id());
	}

}
