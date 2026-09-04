package com.jlagp.libretacobro.mailer;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import com.jlagp.libretacobro.outbox.EmailPendiente;
import com.jlagp.libretacobro.outbox.OutboxStore;

/**
 * Adapter JDBC del SPI {@link OutboxStore} para el worker. Espejo del SQL de
 * {@code EmailsPendientesMapper} del WAR (misma tabla, mismas transiciones);
 * la tabla {@code EmailsPendientes} es el CONTRATO entre ambos procesos —
 * cualquier cambio de esquema debe aterrizar en los dos lados.
 *
 * <p>{@link #insertar} está deliberadamente bloqueado: el encolado es del WAR
 * (dentro de sus transacciones de negocio); este proceso sólo despacha.</p>
 */
@Component
public class JdbcOutboxStore implements OutboxStore {

	private final JdbcTemplate jdbc;

	public JdbcOutboxStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	private static final RowMapper<EmailPendiente> FILA = (rs, i) -> {
		EmailPendiente e = new EmailPendiente();
		e.setId(rs.getLong("id"));
		e.setTipo(rs.getString("tipo"));
		e.setDestinatario(rs.getString("destinatario"));
		e.setAsunto(rs.getString("asunto"));
		e.setCuerpo(rs.getString("cuerpo"));
		e.setAdjunto(rs.getBytes("adjunto"));
		e.setAdjuntoNombre(rs.getString("adjunto_nombre"));
		e.setAdjuntoMime(rs.getString("adjunto_mime"));
		e.setEstado(rs.getString("estado"));
		e.setIntentos(rs.getInt("intentos"));
		e.setReferenciaId(rs.getString("referencia_id"));
		e.setFromDisplay(rs.getString("from_display"));
		e.setReplyTo(rs.getString("reply_to"));
		return e;
	};

	@Override
	public void insertar(EmailPendiente email) {
		throw new UnsupportedOperationException(
				"El worker no encola: el INSERT vive en el WAR, dentro de la TX de negocio.");
	}

	@Override
	public List<EmailPendiente> picker(LocalDateTime ahora, int limite) {
		return jdbc.query("""
				SELECT id, tipo, destinatario, asunto, cuerpo,
				       adjunto, adjunto_nombre, adjunto_mime,
				       estado, intentos, referencia_id, from_display, reply_to
				  FROM EmailsPendientes
				 WHERE estado IN ('PENDIENTE','REINTENTAR')
				   AND proximo_intento_en <= ?
				 ORDER BY proximo_intento_en ASC
				 LIMIT ?""", FILA, ahora, limite);
	}

	@Override
	public void marcarEnviado(Long id, LocalDateTime ahora) {
		// adjunto=NULL: paridad con el mapper del WAR (retención 2026-08-18) —
		// el PDF ya viajó; conservarlo infla la tabla que el picker lee cada 30s.
		jdbc.update("UPDATE EmailsPendientes SET estado='ENVIADO', enviado_en=?, error_ultimo=NULL, adjunto=NULL WHERE id=?",
				ahora, id);
	}

	@Override
	public void marcarReintento(Long id, LocalDateTime proximoIntento, String errorUltimo) {
		jdbc.update("""
				UPDATE EmailsPendientes
				   SET estado='REINTENTAR', intentos=intentos+1,
				       proximo_intento_en=?, error_ultimo=?
				 WHERE id=?""", proximoIntento, recortar(errorUltimo), id);
	}

	@Override
	public void marcarFallido(Long id, String errorUltimo) {
		jdbc.update("UPDATE EmailsPendientes SET estado='FALLIDO', intentos=intentos+1, error_ultimo=? WHERE id=?",
				recortar(errorUltimo), id);
	}

	/** error_ultimo es VARCHAR(500) — mismo recorte defensivo que el WAR. */
	private static String recortar(String error) {
		if (error == null) return null;
		return error.length() <= 500 ? error : error.substring(0, 500);
	}
}
