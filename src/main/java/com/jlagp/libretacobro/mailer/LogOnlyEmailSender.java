package com.jlagp.libretacobro.mailer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import com.jlagp.libretacobro.outbox.EmailPendiente;
import com.jlagp.libretacobro.outbox.EmailSender;

/**
 * Fallback sin SMTP configurado (desarrollo): registra el envío en el log y
 * lo da por exitoso — la fila queda ENVIADA, igual que el LogOnlyMailService
 * del WAR. Así el ciclo completo del worker se puede probar sin servidor de
 * correo.
 */
@Component
@ConditionalOnMissingBean(name = "smtpEmailSender")
public class LogOnlyEmailSender implements EmailSender {

	private static final Logger logger = LoggerFactory.getLogger(LogOnlyEmailSender.class);

	@Override
	public void enviar(EmailPendiente e) {
		logger.info("[MAILER-LOG] (sin SMTP) 'enviado' a {} — {}{}",
				e.getDestinatario(), e.getAsunto(),
				e.tieneAdjunto() ? " (adjunto " + e.getAdjuntoNombre() + ", "
						+ e.getAdjunto().length + " bytes)" : "");
	}
}
