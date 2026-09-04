package com.jlagp.libretacobro.mailer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.jlagp.libretacobro.outbox.EmailPendiente;
import com.jlagp.libretacobro.outbox.EmailSender;

/**
 * Ciclo completo del worker contra H2 (sin SMTP real):
 *  - éxito → ENVIADO
 *  - fallo → REINTENTAR con backoff
 *  - fallo persistente al máximo de intentos → FALLIDO + drop auditado en
 *    Auditoria con el evento del tipo (paridad con el WAR).
 */
@JdbcTest
@Import(JdbcOutboxStore.class)
@DisplayName("MailerDispatcher [integration]")
class MailerDispatcherTest {

    @Autowired private JdbcOutboxStore store;
    @Autowired private JdbcTemplate jdbc;

    private long seed(String tipo, int intentos) {
        jdbc.update("""
                INSERT INTO EmailsPendientes (tipo, destinatario, asunto, cuerpo, estado, intentos,
                                              proximo_intento_en, referencia_id)
                VALUES (?, 'a@x.com', 'asunto', '<p>hola</p>', 'PENDIENTE', ?, ?, 'REF-1')""",
                tipo, intentos, LocalDateTime.now().minusMinutes(1));
        return jdbc.queryForObject("SELECT MAX(id) FROM EmailsPendientes", Long.class);
    }

    private MailerDispatcher dispatcher(EmailSender sender) {
        return new MailerDispatcher(store, sender, jdbc, new MailerHealthCheck(jdbc, 30_000, 10_000));
    }

    @Test
    @DisplayName("envío exitoso: la fila queda ENVIADO")
    void exito() {
        long id = seed(EmailPendiente.TIPO_PAGO_RECIBO, 0);
        AtomicInteger enviados = new AtomicInteger();

        dispatcher(e -> enviados.incrementAndGet()).tick();

        assertThat(enviados.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT estado FROM EmailsPendientes WHERE id=?", String.class, id))
                .isEqualTo("ENVIADO");
    }

    @Test
    @DisplayName("fallo transitorio: REINTENTAR con próximo intento futuro; el tick no explota")
    void falloTransitorio() {
        long id = seed("GENERICO", 0);

        dispatcher(e -> { throw new RuntimeException("SMTP caído"); }).tick();

        Map<String, Object> fila = jdbc.queryForMap("SELECT * FROM EmailsPendientes WHERE id=?", id);
        assertThat(fila.get("estado")).isEqualTo("REINTENTAR");
        assertThat((String) fila.get("error_ultimo")).contains("SMTP caído");
    }

    @Test
    @DisplayName("fallo al máximo de intentos: FALLIDO + drop auditado como PAGO_MAIL_FAIL")
    void dropAuditado() {
        // Sembrado al borde del tope: el siguiente fallo es el drop definitivo.
        long id = seed(EmailPendiente.TIPO_PAGO_RECIBO,
                com.jlagp.libretacobro.outbox.OutboxProcessor.MAX_INTENTOS - 1);

        dispatcher(e -> { throw new RuntimeException("rechazado"); }).tick();

        assertThat(jdbc.queryForObject("SELECT estado FROM EmailsPendientes WHERE id=?", String.class, id))
                .isEqualTo("FALLIDO");
        Map<String, Object> audit = jdbc.queryForMap("SELECT * FROM Auditoria ORDER BY id_auditoria DESC LIMIT 1");
        assertThat(audit.get("accion")).isEqualTo("PAGO_MAIL_FAIL");
        assertThat(audit.get("usuario")).isEqualTo("mailer");
        assertThat((String) audit.get("detalles")).contains("a@x.com");
    }
}
