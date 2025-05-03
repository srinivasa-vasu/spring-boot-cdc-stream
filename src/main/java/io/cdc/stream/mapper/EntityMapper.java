package io.cdc.stream.mapper;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;

public class EntityMapper {

	private static final ObjectMapper objectMapper = new ObjectMapper()
		.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
		.setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
		.registerModule(new JavaTimeModule());

	private static final String SKIP_FIELD = "set";

	/**
	 * Convert a Struct to an entity class using Jackson
	 */
	public <T> T mapStructToEntity(Struct struct, Class<T> entityClass) {
		Map<String, Object> data = structToMap(struct);
		return objectMapper.convertValue(structToMap(struct), entityClass);
	}

	/**
	 * Convert a Struct to a Map
	 */
	private Map<String, Object> structToMap(Struct struct) {
		Map<String, Object> result = new HashMap<>();
		Schema schema = struct.schema();

		for (Field field : schema.fields()) {
			String fieldName = field.name();
			if (!SKIP_FIELD.equals(fieldName)) {
				Object value = struct.get(field);
				if (value instanceof Struct) {
					result.put(fieldName, structToMap((Struct) value));
				}
				else {
					result.put(fieldName, value);
				}
			}
		}
		return result;
	}

}