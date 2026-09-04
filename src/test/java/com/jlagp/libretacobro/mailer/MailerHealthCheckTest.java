package com.jlagp.libretacobro.mailer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Estados del health check contra H2:
 *  - recién arrancado / tick OK → ARRIBA sin errores
 *  - último tick fallido → ARRIBA con errores (degradado, va a reintentar)
 *  - sin ticks pasada la tolerancia → CAÍDO (scheduler atascado)
 *  - BD inaccesible → CAÍDO
 */
@JdbcTest
@DisplayName("MailerHealthCheck [integration]")
class MailerHealthCheckTest {

    @Autowired private JdbcTemplate jdbc;

    private static final long POLL_MS = 30_000;
    private static final long INITIAL_DELAY_MS = 10_000;

    private MailerHealthCheck health() {
        return new MailerHealthCheck(jdbc, POLL_MS, INITIAL_DELAY_MS);
    }

    @Test
    @DisplayName("recién arrancado, sin ticks todavía: ARRIBA sin errores")
    void arranqueLimpio() {
        MailerHealthCheck.Estado estado = health().verificar();

        assertThat(estado.arriba()).isTrue();
        assertThat(estado.tieneErrores()).isFalse();
    }

    @Test
    @DisplayName("tick exitoso: ARRIBA sin errores y con marca del último tick OK")
    void tickOk() {
        MailerHealthCheck health = health();
        health.registrarTickOk();

        MailerHealthCheck.Estado estado = health.verificar();

        assertThat(estado.arriba()).isTrue();
        assertThat(estado.tieneErrores()).isFalse();
        assertThat(estado.ultimoTickOk()).isNotNull();
    }

    @Test
    @DisplayName("último tick fallido: ARRIBA pero con el error reportado")
    void tickFallido() {
        MailerHealthCheck health = health();
        health.registrarTickError("SMTP caído");

        MailerHealthCheck.Estado estado = health.verificar();

        assertThat(estado.arriba()).isTrue();
        assertThat(estado.errores()).anySatisfy(e -> assertThat(e).contains("SMTP caído"));
    }

    @Test
    @DisplayName("un tick OK posterior limpia el error del tick anterior")
    void seRecupera() {
        MailerHealthCheck health = health();
        health.registrarTickError("SMTP caído");
        health.registrarTickOk();

        assertThat(health.verificar().tieneErrores()).isFalse();
    }

    @Test
    @DisplayName("sin tick exitoso pasada la tolerancia: CAÍDO por despacho atascado")
    void despachoAtascado() {
        MailerHealthCheck health = health();
        LocalDateTime futuro = LocalDateTime.now()
                .plusSeconds((INITIAL_DELAY_MS + 3 * POLL_MS) / 1000 + 60);

        MailerHealthCheck.Estado estado = health.verificar(futuro);

        assertThat(estado.arriba()).isFalse();
        assertThat(estado.errores()).anySatisfy(e -> assertThat(e).contains("atascado"));
    }

    @Test
    @DisplayName("BD inaccesible: CAÍDO aunque el tick venga reportando OK")
    void bdInaccesible() {
        // JdbcTemplate sin DataSource: el SELECT 1 revienta como en una BD caída.
        MailerHealthCheck health = new MailerHealthCheck(new JdbcTemplate(), POLL_MS, INITIAL_DELAY_MS);
        health.registrarTickOk();

        MailerHealthCheck.Estado estado = health.verificar();

        assertThat(estado.arriba()).isFalse();
        assertThat(estado.errores()).anySatisfy(e -> assertThat(e).contains("BD inaccesible"));
    }
}
