# libretacobro-mailer

Worker de envío de correos de Libreta de Cobro — **primer servicio extraído del
monolito** (análisis de microservicios 2026-08-16: el outbox transaccional es la
frontera natural).

## Reparto de responsabilidades

| Proceso | Hace | No hace |
|---|---|---|
| WAR (`libretaCobroAPI`) | **Encola**: escribe filas completas (asunto, cuerpo HTML, adjunto, remitente) en `EmailsPendientes` dentro de sus transacciones de negocio | Despachar (con el flag apagado) |
| Este worker | **Despacha**: poll cada 30s + envío SMTP + backoff exponencial (1m→24h, 8 intentos) + drop auditado en `Auditoria` | Encolar, plantillas, PDFs, lógica de negocio |

La tabla `EmailsPendientes` **es el contrato** entre ambos y desde 2026-09-04 está
escrito en código: `OutboxContrato` (jar compartido `libretacobro-outbox` 0.1.1) lista
las columnas y los estados, y `ContratoOutboxCheck` lo verifica al arrancar contra la BD
— si falta una columna el worker **no arranca** (fallo claro en el log en vez de ticks
rotos). El WAR hace la misma verificación de su lado; el procedimiento para cambiar el
contrato está en `docs/contrato-outbox.md` del WAR. La máquina de estados vive en el
mismo jar.

## Operación

```bash
# Compilar (requiere libretacobro-outbox instalado en el repo local)
mvn package

# Correr (dev: sin SMTP → modo log-only, marca ENVIADO y registra en log)
java -jar target/libretacobro-mailer-0.0.1-SNAPSHOT.jar
```

Variables (mismos nombres que el WAR): `MAILER_DB_URL/USER/PASSWORD`,
`SPRING_MAIL_HOST/PORT/USERNAME`, `SMTP_PASSWORD`, `APP_MAIL_FROM`,
`OUTBOX_POLL_MS`.

## Regla de oro: UN solo dispatcher activo

Al poner este worker en marcha, apagar el tick del WAR:

```
APP_OUTBOX_DISPATCHER_ENABLED=false   # en el entorno del WAR
```

El WAR sigue encolando igual que siempre (su `enqueue` no cambia). Correr ambos
dispatchers no corrompe nada (at-least-once + UPDATEs atómicos por fila) pero
puede duplicar correos. Rollback del cambio completo = apagar el worker y
regresar el flag a `true`.

## Despliegue como servicio (systemd)

```bash
mvn -q package -DskipTests
sudo mkdir -p /opt/libretacobro-mailer && sudo cp target/libretacobro-mailer-0.1.0.jar /opt/libretacobro-mailer/libretacobro-mailer.jar
sudo cp ops/systemd/libretacobro-mailer.service /etc/systemd/system/
sudo install -m 600 /dev/null /etc/libretacobro-mailer.env   # y llenar las variables
sudo systemctl daemon-reload && sudo systemctl enable --now libretacobro-mailer
journalctl -u libretacobro-mailer -f
```
El WAR debe correr con `APP_OUTBOX_DISPATCHER_ENABLED=false` mientras este worker esté activo.
