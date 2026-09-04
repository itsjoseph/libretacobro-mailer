package com.jlagp.libretacobro.mailer;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.jlagp.libretacobro.outbox.EmailPendiente;
import com.jlagp.libretacobro.outbox.EmailSender;
import com.jlagp.libretacobro.outbox.OutboxProcessor;

/**
 * Tick del worker: delega TODO en {@link OutboxProcessor} (picker → envío →
 * marcarEnviado/backoff/drop). Misma cadencia default que el dispatcher del
 * WAR (30s). Un fallo del tick no envenena el scheduler (catch a nivel de
 * tick, patrón de los jobs del WAR).
 *
 * <p>El drop definitivo (agotó los 8 intentos) se audita en la MISMA tabla
 * {@code Auditoria} y con los mismos eventos {@code *_MAIL_FAIL} que usaba el
 * WAR — la operación no nota el cambio de proceso.</p>
 */
@Component
public class MailerDispatcher {

	private static final Logger logger = LoggerFactory.getLogger(MailerDispatcher.class);

	private final OutboxProcessor processor;
	private final MailerHealthCheck health;

	public MailerDispatcher(JdbcOutboxStore store, EmailSender sender, JdbcTemplate jdbc,
			MailerHealthCheck health) {
		this.processor = new OutboxProcessor(store, sender,
				(email, error, intentos) -> auditarDrop(jdbc, email, error, intentos));
		this.health = health;
	}

	@Scheduled(fixedDelayString = "${app.outbox.pollMs:30000}",
	           initialDelayString = "${app.outbox.initialDelayMs:10000}")
	public void tick() {
		try {
			processor.procesarPendientes();
			health.registrarTickOk();
		} catch (RuntimeException e) {
			health.registrarTickError(e.getMessage());
			logger.error("[MAILER] tick falló: {}", e.getMessage(), e);
		}
	}

	/** Mismo mapeo tipo→evento que EmailsOutboxService.auditarDrop del WAR. */
	private static void auditarDrop(JdbcTemplate jdbc, EmailPendiente e, String error, int intentos) {
		String accion;
		String entidad;
		switch (e.getTipo()) {
			case EmailPendiente.TIPO_PAGO_RECIBO:       accion = "PAGO_MAIL_FAIL"; entidad = "Pago"; break;
			case EmailPendiente.TIPO_VENTA_COMPROBANTE: accion = "VENTA_MAIL_FAIL"; entidad = "Venta"; break;
			default:                                    accion = "MAIL_FAIL"; entidad = "Email"; break;
		}
		try {
			jdbc.update("""
					INSERT INTO Auditoria (fecha, usuario, accion, entidad, entidad_id, detalles, ip)
					VALUES (?, 'mailer', ?, ?, ?, ?, NULL)""",
					LocalDateTime.now(), accion, entidad, e.getReferenciaId(),
					"dest=" + e.getDestinatario() + ", intentos=" + intentos + ", error=" + error);
		} catch (RuntimeException ex) {
			// La auditoría del drop es best-effort: no debe tumbar el tick.
			logger.warn("[MAILER] no se pudo auditar el drop de {}: {}", e.getReferenciaId(), ex.getMessage());
		}
	}
}
