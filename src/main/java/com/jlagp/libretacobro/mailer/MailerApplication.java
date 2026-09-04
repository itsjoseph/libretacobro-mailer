package com.jlagp.libretacobro.mailer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Worker de envío de correos — primer servicio extraído del monolito
 * libretaCobroAPI (análisis de microservicios 2026-08-16: el outbox
 * transaccional es la frontera natural).
 *
 * <p>Reparto de responsabilidades:
 * <ul>
 *   <li><b>El WAR</b> conserva el ENCOLADO: escribe filas completas
 *       (asunto/cuerpo HTML/adjunto/remitente) en {@code EmailsPendientes}
 *       dentro de sus transacciones de negocio. Rollback ⇒ no hay email.</li>
 *   <li><b>Este worker</b> sólo DESPACHA: poll de la tabla + envío SMTP +
 *       backoff exponencial + drop auditado, con la máquina de estados del
 *       módulo compartido {@code libretacobro-outbox}. Cero lógica de
 *       negocio, cero plantillas, cero PDFs.</li>
 * </ul></p>
 *
 * <p><b>Un solo dispatcher activo</b>: al desplegar este worker, apagar el
 * tick del WAR con {@code app.outbox.dispatcher.enabled=false}. Correr ambos
 * no corrompe nada (at-least-once + UPDATEs atómicos por fila) pero puede
 * duplicar correos.</p>
 */
@SpringBootApplication
@EnableScheduling
public class MailerApplication {

	public static void main(String[] args) {
		SpringApplication.run(MailerApplication.class, args);
	}
}
