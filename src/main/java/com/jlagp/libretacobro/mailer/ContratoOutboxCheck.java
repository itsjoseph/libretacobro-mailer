package com.jlagp.libretacobro.mailer;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.jlagp.libretacobro.outbox.OutboxContrato;

/**
 * Verificación del contrato con el WAR al arrancar (paso 7, 2026-09-04): la
 * tabla {@code EmailsPendientes} debe tener todas las columnas que este worker
 * lee y escribe ({@link OutboxContrato#COLUMNAS}). Si falta una, el worker NO
 * arranca: mejor un fallo claro en el log que ticks fallando cada 30 s (o peor,
 * correos despachados con datos a medias). El WAR hace la misma verificación
 * de su lado.
 */
@Component
public class ContratoOutboxCheck implements ApplicationRunner {

	private static final Logger logger = LoggerFactory.getLogger(ContratoOutboxCheck.class);

	private final DataSource dataSource;

	public ContratoOutboxCheck(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public void run(ApplicationArguments args) throws SQLException {
		List<String> faltan;
		try (Connection cn = dataSource.getConnection()) {
			faltan = OutboxContrato.columnasFaltantes(cn);
		}
		decidir(faltan);
		logger.info("[MAILER] {} verificado; esquema del WAR: {}", OutboxContrato.descripcion(), versionEsquema());
	}

	/** Pura, para tests: con columnas faltantes aborta el arranque nombrándolas. */
	static void decidir(List<String> faltan) {
		if (faltan.isEmpty()) return;
		String msg = OutboxContrato.TABLA + " no cumple el " + OutboxContrato.descripcion()
				+ " — faltan: " + String.join(", ", faltan)
				+ ". Aplica la migración del WAR o actualiza este worker al jar libretacobro-outbox nuevo.";
		logger.error("[MAILER] ARRANQUE ABORTADO: {}", msg);
		throw new IllegalStateException("ARRANQUE ABORTADO: " + msg);
	}

	/** Última migración registrada por el WAR (tabla SchemaVersion), sólo informativo. */
	private String versionEsquema() {
		try {
			// version es texto ("V056"), no número.
			String v = new JdbcTemplate(dataSource).queryForObject("SELECT MAX(version) FROM SchemaVersion", String.class);
			return v == null ? "sin registro" : v;
		} catch (RuntimeException e) {
			return "desconocido (sin tabla SchemaVersion)";
		}
	}
}
