package com.jlagp.libretacobro.mailer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import com.jlagp.libretacobro.outbox.EmailPendiente;
import com.jlagp.libretacobro.outbox.EmailSender;

import jakarta.mail.internet.MimeMessage;

/**
 * {@link EmailSender} real por SMTP. Espejo fiel del {@code SmtpMailService}
 * del WAR: cuerpo HTML detectado por prefijo {@code <}, From con nombre
 * visible del negocio pero DIRECCIÓN de la cuenta SMTP autenticada (no rompe
 * SPF/DKIM/DMARC), Reply-To al correo de contacto del negocio, adjuntos por
 * MimeMessageHelper.
 *
 * <p>Contrato del SPI: un fallo de envío se señala con RuntimeException — el
 * OutboxProcessor programa el reintento con backoff.</p>
 *
 * <p>Se activa sólo con {@code spring.mail.host} configurado (mismo criterio
 * SpEL que el WAR); sin SMTP toma su lugar {@link LogOnlyEmailSender}.</p>
 */
@Component("smtpEmailSender")
@ConditionalOnExpression("'${spring.mail.host:}'.length() > 0")
public class SmtpEmailSender implements EmailSender {

	private static final Logger logger = LoggerFactory.getLogger(SmtpEmailSender.class);

	private final JavaMailSender sender;

	@Value("${app.mail.from:no-reply@libretacobro.local}")
	private String from;

	public SmtpEmailSender(JavaMailSender sender) {
		this.sender = sender;
	}

	@Override
	public void enviar(EmailPendiente e) {
		try {
			MimeMessage msg = sender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(msg, e.tieneAdjunto(), "UTF-8");
			aplicarRemitente(helper, e.getFromDisplay(), e.getReplyTo());
			helper.setTo(e.getDestinatario());
			helper.setSubject(e.getAsunto());
			helper.setText(e.getCuerpo(), esHtml(e.getCuerpo()));
			if (e.tieneAdjunto()) {
				helper.addAttachment(e.getAdjuntoNombre(),
						new ByteArrayResource(e.getAdjunto()), e.getAdjuntoMime());
			}
			sender.send(msg);
			logger.info("[MAILER-SMTP] enviado a {} — {}{}", e.getDestinatario(), e.getAsunto(),
					e.tieneAdjunto() ? " (con adjunto " + e.getAdjuntoNombre() + ")" : "");
		} catch (RuntimeException ex) {
			logger.error("[MAILER-SMTP] error enviando a {} ({}): {}",
					e.getDestinatario(), e.getAsunto(), ex.getMessage());
			throw ex;
		} catch (Exception ex) {
			logger.error("[MAILER-SMTP] error enviando a {} ({}): {}",
					e.getDestinatario(), e.getAsunto(), ex.getMessage());
			throw new RuntimeException("No se pudo enviar el correo", ex);
		}
	}

	private void aplicarRemitente(MimeMessageHelper helper, String fromDisplay, String replyTo)
			throws jakarta.mail.MessagingException, java.io.UnsupportedEncodingException {
		if (fromDisplay != null && !fromDisplay.isBlank()) {
			helper.setFrom(from, fromDisplay.trim());
		} else {
			helper.setFrom(from);
		}
		if (replyTo != null && !replyTo.isBlank()) {
			helper.setReplyTo(replyTo.trim());
		}
	}

	private static boolean esHtml(String body) {
		return body != null && body.stripLeading().startsWith("<");
	}
}
