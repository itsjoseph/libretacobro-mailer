package com.jlagp.libretacobro.mailer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Import;

import com.jlagp.libretacobro.outbox.EmailPendiente;

/**
 * Contrato del adapter contra H2: picker (estados/fecha/orden/límite) y las
 * tres transiciones, espejo del mapper del WAR. insertar() está vetado.
 */
@JdbcTest
@Import(JdbcOutboxStore.class)
@DisplayName("JdbcOutboxStore [integration]")
class JdbcOutboxStoreTest {

    @Autowired private JdbcOutboxStore store;
    @Autowired private JdbcTemplate jdbc;

    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 8, 16, 12, 0);

    private long seed(String estado, LocalDateTime proximo) {
        jdbc.update("""
                INSERT INTO EmailsPendientes (tipo, destinatario, asunto, cuerpo, estado, proximo_intento_en)
                VALUES ('GENERICO', 'a@x.com', 'asunto', '<p>hola</p>', ?, ?)""", estado, proximo);
        return jdbc.queryForObject("SELECT MAX(id) FROM EmailsPendientes", Long.class);
    }

    @Test
    @DisplayName("picker: sólo PENDIENTE/REINTENTAR vencidos, orden por próximo intento, respeta límite")
    void picker() {
        long listo = seed("PENDIENTE", AHORA.minusMinutes(5));
        seed("REINTENTAR", AHORA.minusMinutes(1));
        seed("PENDIENTE", AHORA.plusHours(1));   // aún no toca
        seed("ENVIADO", AHORA.minusHours(1));    // terminal
        seed("FALLIDO", AHORA.minusHours(1));    // terminal

        List<EmailPendiente> lote = store.picker(AHORA, 10);

        assertThat(lote).hasSize(2);
        assertThat(lote.get(0).getId()).isEqualTo(listo); // el más antiguo primero
        assertThat(store.picker(AHORA, 1)).hasSize(1);
    }

    @Test
    @DisplayName("transiciones: enviado limpia error; reintento y fallido incrementan intentos")
    void transiciones() {
        long id = seed("PENDIENTE", AHORA);

        store.marcarReintento(id, AHORA.plusMinutes(1), "x".repeat(600)); // se recorta a 500
        Map<String, Object> fila = jdbc.queryForMap("SELECT * FROM EmailsPendientes WHERE id=?", id);
        assertThat(fila.get("estado")).isEqualTo("REINTENTAR");
        assertThat(((Number) fila.get("intentos")).intValue()).isEqualTo(1);
        assertThat(((String) fila.get("error_ultimo"))).hasSize(500);

        // Simular un adjunto pendiente para verificar que ENVIADO lo suelta
        // (paridad con el mapper del WAR, retención 2026-08-18).
        jdbc.update("UPDATE EmailsPendientes SET adjunto=? WHERE id=?", new byte[]{1,2,3}, id);
        store.marcarEnviado(id, AHORA.plusMinutes(2));
        fila = jdbc.queryForMap("SELECT * FROM EmailsPendientes WHERE id=?", id);
        assertThat(fila.get("estado")).isEqualTo("ENVIADO");
        assertThat(fila.get("error_ultimo")).isNull();
        assertThat(fila.get("adjunto")).isNull();

        long id2 = seed("REINTENTAR", AHORA);
        store.marcarFallido(id2, "boom");
        fila = jdbc.queryForMap("SELECT * FROM EmailsPendientes WHERE id=?", id2);
        assertThat(fila.get("estado")).isEqualTo("FALLIDO");
        assertThat(((Number) fila.get("intentos")).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("insertar: vetado — el encolado es del WAR")
    void insertarVetado() {
        assertThatThrownBy(() -> store.insertar(new EmailPendiente()))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
