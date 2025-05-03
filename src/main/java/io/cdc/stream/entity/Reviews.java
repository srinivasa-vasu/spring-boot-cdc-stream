package io.cdc.stream.entity;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.cdc.stream.mapper.KeyValueDeserializer;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Entity
@Data
@Accessors(fluent = true)
@AllArgsConstructor
@NoArgsConstructor
public class Reviews extends Base {

	@Id
	@JsonDeserialize(using = KeyValueDeserializer.class)
	Long id;

	@JsonDeserialize(using = KeyValueDeserializer.class)
	String reviewer;

	@JsonDeserialize(using = KeyValueDeserializer.class)
	BigInteger productId;

	@JsonDeserialize(using = KeyValueDeserializer.class)
	BigDecimal rating;

	@JsonDeserialize(using = KeyValueDeserializer.class)
	String body;

	@JsonDeserialize(using = KeyValueDeserializer.class)
	Instant createdAt;

}
