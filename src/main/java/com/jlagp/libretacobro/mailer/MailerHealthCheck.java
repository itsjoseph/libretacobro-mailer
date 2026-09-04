package com.jlagp.libretacobro.mailer;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Salud del worker: responde si el componente está ARRIBA o tiene errores.
 *
 * <p>Tres señales, de más a menos grave:
 * <ul>
 *   <li><b>BD inaccesible</b> ({@code SELECT 1} falla) → CAÍDO. Sin BD no hay
 *       outbox que despachar.</li>
 *   <li><b>Despacho atascado</b>: sin tick exitoso dentro de la tolerancia
 *       (initialDelay + 3 ciclos de poll) → CAÍDO. Cubre el scheduler muerto
 *       o bloqueado, que un simple ping de BD no detecta.</li>
 *   <li><b>Último tick falló</b> → ARRIBA con errores (degradado): el proceso
 *       vive y va a reintentar en el próximo ciclo.</li>
 * </ul></p>
 *
 * <p>Este proceso es headless (sin actuator/HTTP), así que el estado se
 * consume por el latido periódico en logs ({@link #reportar}) o llamando a
 * {@link #verificar()} desde código. Si mañana se expone un endpoint de
 * health, esta clase es la fuente única del estado.</p>
 */
@Component
public class MailerHealthCheck {

	private static final Logger logger = LoggerFactory.getLogger(MailerHealthCheck.class);

	private final JdbcTemplate jdbc;
	private final long toleranciaMs;
	private final LocalDateTime arranque = LocalDateTime.now();

	private volatile LocalDateTime ultimoTickOk;
	private volatile LocalDateTime ultimoTickError;
	private volatile String ultimoError;

	public MailerHealthCheck(JdbcTemplate jdbc,
			@Value("${app.outbox.pollMs:30000}") long pollMs,
			@Value("${app.outbox.initialDelayMs:10000}") long initialDelayMs) {
		this.jdbc = jdbc;
		this.toleranciaMs = initialDelayMs + 3 * pollMs;
	}

	/** Estado del componente en un instante: arriba/caído + errores detectados. */
	public record Estado(boolean arriba, List<String> errores, LocalDateTime ultimoTickOk) {
		public boolean tieneErrores() {
			return !errores.isEmpty();
		}
	}

	/** El dispatcher reporta aquí el resultado de cada tick. */
	void registrarTickOk() {
		ultimoTickOk = LocalDateTime.now();
	}

	void registrarTickError(String error) {
		ultimoTickError = LocalDateTime.now();
		ultimoError = recortar(error);
	}

	public Estado verificar() {
		return verificar(LocalDateTime.now());
	}

	/** Visible para tests: permite evaluar la tolerancia sin esperar de verdad. */
	Estado verificar(LocalDateTime ahora) {
		List<String> errores = new ArrayList<>();
		boolean arriba = true;

		try {
			jdbc.queryForObject("SELECT 1", Integer.class);
		} catch (RuntimeException e) {
			arriba = false;
			errores.add("BD inaccesible: " + recortar(e.getMessage()));
		}

		// Antes del primer tick la referencia es el arranque: recién iniciado
		// cuenta como sano mientras no agote la tolerancia.
		LocalDateTime referencia = (ultimoTickOk != null) ? ultimoTickOk : arranque;
		if (Duration.between(referencia, ahora).toMillis() > toleranciaMs) {
			arriba = false;
			errores.add("despacho atascado: sin tick exitoso desde " + referencia);
		}

		if (ultimoTickError != null && (ultimoTickOk == null || ultimoTickError.isAfter(ultimoTickOk))) {
			errores.add("el último tick falló: " + ultimoError);
		}

		return new Estado(arriba, List.copyOf(errores), ultimoTickOk);
	}

	/** Latido en logs: única salida del estado mientras el worker sea headless. */
	@Scheduled(fixedDelayString = "${app.health.logMs:300000}",
	           initialDelayString = "${app.health.initialDelayMs:60000}")
	public void reportar() {
		Estado estado = verificar();
		if (!estado.arriba()) {
			logger.error("[MAILER-HEALTH] CAÍDO: {}", String.join("; ", estado.errores()));
		} else if (estado.tieneErrores()) {
			logger.warn("[MAILER-HEALTH] ARRIBA con errores: {}", String.join("; ", estado.errores()));
		} else {
			logger.debug("[MAILER-HEALTH] ARRIBA — último tick OK: {}", estado.ultimoTickOk());
		}
	}

	private static String recortar(String error) {
		if (error == null) return "(sin mensaje)";
		return error.length() <= 200 ? error : error.substring(0, 200);
	}
}
