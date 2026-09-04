-- Espejo mínimo de las tablas de producción que el worker toca.
-- EmailsPendientes ES el contrato con el WAR (misma forma que V027+V046).
CREATE TABLE IF NOT EXISTS EmailsPendientes (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    tipo               VARCHAR(40)  NOT NULL,
    destinatario       VARCHAR(150) NOT NULL,
    asunto             VARCHAR(255) NOT NULL,
    cuerpo             TEXT         NOT NULL,
    adjunto            BLOB,
    adjunto_nombre     VARCHAR(255),
    adjunto_mime       VARCHAR(100),
    estado             VARCHAR(20)  NOT NULL DEFAULT 'PENDIENTE',
    intentos           INT          NOT NULL DEFAULT 0,
    proximo_intento_en DATETIME     NOT NULL,
    creado_en          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    enviado_en         DATETIME,
    error_ultimo       VARCHAR(500),
    referencia_id      VARCHAR(64),
    from_display       VARCHAR(160),
    reply_to           VARCHAR(150)
);

CREATE TABLE IF NOT EXISTS Auditoria (
    id_auditoria BIGINT AUTO_INCREMENT PRIMARY KEY,
    fecha        DATETIME,
    usuario      VARCHAR(50),
    accion       VARCHAR(60),
    entidad      VARCHAR(40),
    entidad_id   VARCHAR(64),
    detalles     VARCHAR(1000),
    ip           VARCHAR(45)
);
