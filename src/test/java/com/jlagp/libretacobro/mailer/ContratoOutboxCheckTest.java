package com.jlagp.libretacobro.mailer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;

import com.jlagp.libretacobro.outbox.OutboxContrato;

/**
 * El worker verifica el contrato con el WAR al arrancar: con el esquema de
 * prueba (espejo de las migraciones) pasa; con una columna menos, aborta.
 */
@JdbcTest
@DisplayName("ContratoOutboxCheck — el worker no arranca si EmailsPendientes no cumple el contrato")
class ContratoOutboxCheckTest {

	@Autowired private DataSource dataSource;

	@Test
	@DisplayName("esquema completo → arranca y no lanza")
	void esquemaCompleto() {
		assertThatCode(() -> new ContratoOutboxCheck(dataSource).run(null)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("tabla sin reply_to → columnasFaltantes la nombra y decidir aborta")
	void columnaFaltante() throws Exception {
		try (Connection cn = DriverManager.getConnection("jdbc:h2:mem:contrato_neg;DB_CLOSE_DELAY=-1")) {
			cn.createStatement().execute("CREATE TABLE EmailsPendientes (id BIGINT, tipo VARCHAR(40), destinatario VARCHAR(150), "
					+ "asunto VARCHAR(255), cuerpo CLOB, adjunto BLOB, adjunto_nombre VARCHAR(255), adjunto_mime VARCHAR(100), "
					+ "estado VARCHAR(20), intentos INT, proximo_intento_en TIMESTAMP, creado_en TIMESTAMP, enviado_en TIMESTAMP, "
					+ "error_ultimo VARCHAR(500), referencia_id VARCHAR(64), from_display VARCHAR(160))");
			List<String> faltan = OutboxContrato.columnasFaltantes(cn);
			assertThat(faltan).containsExactly("reply_to");
			assertThatThrownBy(() -> ContratoOutboxCheck.decidir(faltan))
					.isInstanceOf(IllegalStateException.class).hasMessageContaining("reply_to");
		}
	}
}
