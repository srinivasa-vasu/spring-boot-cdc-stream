package io.cdc.stream.service;

import java.util.List;

public interface PersistentService<T> {

	void delete(T entity);

	void save(T entity);

	void save(List<T> objects);

	void delete(List<T> objects);

}
