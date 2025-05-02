package io.cdc.stream.entity;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.cdc.stream.mapper.KeyValueDeserializer;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.math.BigDecimal;
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
public class Users extends Base {
	@Id
	@JsonDeserialize(using = KeyValueDeserializer.class)
	Long id;
	@JsonDeserialize(using = KeyValueDeserializer.class)
	String name;
	@JsonDeserialize(using = KeyValueDeserializer.class)
	String email;
	@JsonDeserialize(using = KeyValueDeserializer.class)
	String address;
	@JsonDeserialize(using = KeyValueDeserializer.class)
	String city;
	@JsonDeserialize(using = KeyValueDeserializer.class)
	String state;
	@JsonDeserialize(using = KeyValueDeserializer.class)
	String zip;
	@JsonDeserialize(using = KeyValueDeserializer.class)
	String birthDate;
	@JsonDeserialize(using = KeyValueDeserializer.class)
	String password;
	@JsonDeserialize(using = KeyValueDeserializer.class)
	String source;
	@JsonDeserialize(using = KeyValueDeserializer.class)
	BigDecimal latitude;
	@JsonDeserialize(using = KeyValueDeserializer.class)
	BigDecimal longitude;
	@JsonDeserialize(using = KeyValueDeserializer.class)
	Instant createdAt;
}
