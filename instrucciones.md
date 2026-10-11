# Instrucciones de compilación, ejecución y pruebas

Guía para compilar, levantar y probar el sistema Banco XYZ en una máquina local con Docker Compose. La descripción de la arquitectura está en el [README](README.md) y la guía de AWS en [despliegue.md](despliegue.md).

Los ejemplos están pensados para **Windows PowerShell 5.1**. Donde corresponde se incluye la variante para bash (Git Bash, Linux o macOS).

## 1. Requisitos

| Herramienta | Versión | Para qué |
|---|---|---|
| JDK | 17 | Compilar y correr los tests unitarios con el Maven wrapper. |
| Maven | No hace falta instalarlo | Se usa el wrapper incluido: `mvnw.cmd` (Windows) o `./mvnw` (bash). |
| Docker Desktop / Docker Engine con Compose v2 | Reciente | Construir las imágenes y levantar los 13 servicios. |
| Memoria para Docker | 8 GB recomendados | El despliegue normal reserva unos 6 GB de límites; el escalado, algo más. |
| PowerShell 5.1 o bash | — | Ejecutar los ejemplos. |

Puertos libres en el host: `3307` (MySQL), `8081`, `8082` y `8083` (nginx → BFF), `8761` (Eureka), `8888` (Config Server) y `9000` (auth-server).

## 2. Compilar y correr los tests unitarios

El wrapper usa el JDK al que apunta `JAVA_HOME`, que debe ser un JDK 17. En PowerShell, por ejemplo:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.19'   # ajustar a la ruta del JDK 17 instalado
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

**Compilar todos los módulos sin tests:**

```powershell
.\mvnw.cmd clean install -DskipTests
```

**Compilar y correr los tests unitarios:**

```powershell
.\mvnw.cmd clean install "-Dtest=!BatchApplicationTests" "-Dsurefire.failIfNoSpecifiedTests=false"
```

En bash: `./mvnw clean install '-Dtest=!BatchApplicationTests' -Dsurefire.failIfNoSpecifiedTests=false`.

Se excluye `BatchApplicationTests` porque es un `@SpringBootTest` que levanta el contexto completo de `servicio-cuentas`, y ese contexto necesita dos cosas:
- **El Config Server en `localhost:8888`:** `spring.config.import=configserver:...` es obligatorio, y de ahí sale la contraseña de la base.
- **MySQL en `localhost:3306`:** la usan JPA y el JobRepository de Spring Batch.

Por eso no es un test unitario y solo pasa con esa infraestructura levantada. El resto son tests unitarios con Mockito; se reparten así:

| Módulo | Tests unitarios |
|---|---|
| `seguridad-interna` | 8 |
| `servicio-cuentas` | 43 |
| `servicio-auditoria` | 8 |
| `servicio-clientes` | 5 |
| `servicio-pagos` | 39 |

**Correr un módulo solo:**

```powershell
.\mvnw.cmd -pl servicio-pagos test
.\mvnw.cmd -pl servicio-cuentas test "-Dtest=!BatchApplicationTests" "-Dsurefire.failIfNoSpecifiedTests=false"
```

No hace falta compilar en el host para levantar el sistema: cada `Dockerfile` compila su módulo dentro de la imagen.

## 3. Levantar el sistema

### Despliegue normal (1 réplica por servicio)

Desde la raíz del repositorio:

```powershell
docker compose up -d --build
docker compose ps
```

Los servicios arrancan en orden según `depends_on` y sus healthchecks. Hay que esperar a que los 13 figuren como `healthy`; la primera vez, con la construcción de las imágenes, tarda varios minutos. Después, conviene esperar unos 30 segundos más para que Eureka propague los registros antes de la primera llamada (ver la sección 9).

La primera vez que se crea el volumen de MySQL se ejecutan los scripts de `db/init/`:
- `00-crear-bases.sql`: crea las bases `banco_clientes`, `banco_pagos` y `banco_auditoria`. La base `banco_xyz` la crea `MYSQL_DATABASE`.
- `01-datos.sql`: datos iniciales de cuentas, transacciones y movimientos.
- `02-batch-schema.sql`: tablas `BATCH_*` de Spring Batch.

### Despliegue escalado (2 réplicas)

```powershell
docker compose -f docker-compose.yaml -f docker-compose.escalado.yaml up -d --build
```

`docker-compose.escalado.yaml` deja 2 réplicas de `servicio-cuentas`, `servicio-pagos` y `bff-cajero`. También se puede escalar cualquier servicio de negocio o BFF con `--scale`:

```powershell
docker compose up -d --scale servicio-cuentas=2 --scale servicio-pagos=2 --scale bff-cajero=2
```

Las réplicas se ven en Eureka (http://localhost:8761, o `GET http://localhost:8761/eureka/apps` con `Accept: application/json`):
- Entre servicios reparte Spring Cloud LoadBalancer.
- Hacia los BFF reparte nginx; su log muestra qué réplica atendió cada conexión: `docker compose logs reverse-proxy`.

Para volver a 1 réplica: `docker compose up -d` (sin el archivo de escalado).

### Detener

```powershell
docker compose down      # detiene y conserva los datos de MySQL
docker compose down -v   # detiene y borra el volumen (la proxima vez se vuelven a cargar los datos iniciales)
```

Si se levantó con el archivo de escalado, hay que bajarlo con los mismos `-f`:

```powershell
docker compose -f docker-compose.yaml -f docker-compose.escalado.yaml down -v
```

## 4. Puertos y accesos

| Acceso | URL | Notas |
|---|---|---|
| Canal web | `https://localhost:8081` | nginx → `bff-web`. Certificado autofirmado: usar `-k` en curl. |
| Canal móvil | `https://localhost:8082` | nginx → `bff-movil`. |
| Canal cajero | `https://localhost:8083` | nginx → `bff-cajero`. |
| Tokens OAuth2 | `http://localhost:9000/oauth2/token` | `auth-server`. |
| Config Server | `http://localhost:8888/{servicio}/default` | Muestra la configuración ya descifrada (solo en desarrollo). |
| Eureka | `http://localhost:8761` | Panel con las instancias registradas. |
| MySQL | `localhost:3307`, usuario `root` | Contraseña de desarrollo: la de `MYSQL_ROOT_PASSWORD` en `docker-compose.yaml`. |

`servicio-cuentas` (8080), `servicio-clientes` (8085), `servicio-pagos` (8086) y `servicio-auditoria` (8084) **no publican puertos**: solo son accesibles dentro de la red de Compose y exigen un token con scope `interno`. La sección 6 muestra cómo llamarlos.

## 5. Tokens OAuth2 y llamadas a los BFF

`auth-server` emite tokens con el flujo `client_credentials` (autenticación `client_secret_basic`, vigencia de 1 hora):

| Cliente | Scope | Lo exige |
|---|---|---|
| `web-client` | `web` | `bff-web` |
| `movil-client` | `movil` | `bff-movil` |
| `cajero-client` | `cajero` | `bff-cajero` |
| `servicios-internos` | `interno` | `servicio-cuentas`, `-clientes`, `-pagos` y `-auditoria` |

Los secretos de desarrollo están cifrados (`{cipher}`) en `config-server/src/main/resources/config-repo/`. El Config Server los entrega descifrados:
- **Clientes de canal:** `http://localhost:8888/auth-server/default`, propiedades `oauth.clients.web.secret`, `oauth.clients.movil.secret` y `oauth.clients.cajero.secret`.
- **`servicios-internos`:** `http://localhost:8888/servicio-pagos/default`, propiedad `servicios-internos.client-secret`.

### PowerShell 5.1

```powershell
function ObtenerToken([string]$Cliente, [string]$Secreto, [string]$Scope) {
    $basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${Cliente}:$Secreto"))
    (Invoke-RestMethod -Method Post -Uri http://localhost:9000/oauth2/token -Headers @{ Authorization = "Basic $basic" } `
        -Body @{ grant_type = 'client_credentials'; scope = $Scope }).access_token
}
$secretos = (Invoke-RestMethod http://localhost:8888/auth-server/default).propertySources[0].source
$tokenWeb    = ObtenerToken 'web-client'    $secretos.'oauth.clients.web.secret'    'web'
$tokenMovil  = ObtenerToken 'movil-client'  $secretos.'oauth.clients.movil.secret'  'movil'
$tokenCajero = ObtenerToken 'cajero-client' $secretos.'oauth.clients.cajero.secret' 'cajero'

# bff-web: cuenta con estado anual, movimientos y transacciones
curl.exe -k -H "Authorization: Bearer $tokenWeb" https://localhost:8081/api/web/cuentas/108
curl.exe -k -H "Authorization: Bearer $tokenWeb" https://localhost:8081/api/web/cuentas/108/movimientos
curl.exe -k -H "Authorization: Bearer $tokenWeb" https://localhost:8081/api/web/transacciones

# bff-movil: datos esenciales y ultimos 10 movimientos
curl.exe -k -H "Authorization: Bearer $tokenMovil" https://localhost:8082/api/movil/cuentas/108
curl.exe -k -H "Authorization: Bearer $tokenMovil" https://localhost:8082/api/movil/cuentas/108/movimientos

# bff-cajero: saldo, validacion y retiro (en PowerShell 5.1 las comillas del JSON van escapadas con \")
curl.exe -k -H "Authorization: Bearer $tokenCajero" https://localhost:8083/api/cajero/cuentas/108/saldo
curl.exe -k -H "Authorization: Bearer $tokenCajero" https://localhost:8083/api/cajero/cuentas/108/validar
curl.exe -k -X POST -H "Authorization: Bearer $tokenCajero" -H "Content-Type: application/json" `
    -d '{\"monto\": 100}' https://localhost:8083/api/cajero/cuentas/108/retiro
```

Respuestas esperadas:
- **Sin token:** `401`.
- **Con el token de otro canal:** `403` (por ejemplo, el token del cajero contra `bff-web`).
- **Retiro con saldo insuficiente:** `409` con `{ "mensaje": "..." }`.

### bash

```bash
SECRETO=$(curl -s http://localhost:8888/auth-server/default | grep -o '"oauth.clients.cajero.secret":"[^"]*"' | cut -d'"' -f4)
TOKEN=$(curl -s -u "cajero-client:$SECRETO" -d grant_type=client_credentials -d scope=cajero \
  http://localhost:9000/oauth2/token | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4)
curl -k -H "Authorization: Bearer $TOKEN" https://localhost:8083/api/cajero/cuentas/108/saldo
curl -k -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"monto": 100}' https://localhost:8083/api/cajero/cuentas/108/retiro
```

## 6. Servicios internos (pagos, clientes, cuentas, auditoría)

Como no publican puertos, se llaman desde dentro de la red de Compose. Lo más simple es abrir un shell en un contenedor (todos tienen `curl`), obtener ahí el token de `servicios-internos` y usar los nombres de servicio como host:

```powershell
docker compose exec servicio-pagos sh
```

Dentro del contenedor (sh):

```sh
SECRETO=$(curl -s http://config-server:8888/servicio-pagos/default | grep -o '"servicios-internos.client-secret":"[^"]*"' | cut -d'"' -f4)
TOKEN=$(curl -s -u "servicios-internos:$SECRETO" -d grant_type=client_credentials -d scope=interno \
  http://auth-server:9000/oauth2/token | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4)
AUTH="Authorization: Bearer $TOKEN"
```

### Pagos con `Idempotency-Key`

```sh
# Deposito: 201 con el pago COMPLETADO
curl -s -X POST -H "$AUTH" -H "Content-Type: application/json" -H "Idempotency-Key: demo-dep-1" \
  -d '{"cuentaId":103,"monto":50}' http://servicio-pagos:8086/api/pagos/deposito

# Misma clave y mismo cuerpo: devuelve el mismo pago (mismo id), sin volver a acreditar
curl -s -X POST -H "$AUTH" -H "Content-Type: application/json" -H "Idempotency-Key: demo-dep-1" \
  -d '{"cuentaId":103,"monto":50}' http://servicio-pagos:8086/api/pagos/deposito

# Misma clave con otro cuerpo: 422
curl -s -w ' %{http_code}\n' -X POST -H "$AUTH" -H "Content-Type: application/json" -H "Idempotency-Key: demo-dep-1" \
  -d '{"cuentaId":103,"monto":999}' http://servicio-pagos:8086/api/pagos/deposito

# Transferencia (saga): 201 COMPLETADO; a una cuenta inexistente queda COMPENSADO (404)
curl -s -X POST -H "$AUTH" -H "Content-Type: application/json" -H "Idempotency-Key: demo-tr-1" \
  -d '{"cuentaOrigen":103,"cuentaDestino":105,"monto":30}' http://servicio-pagos:8086/api/pagos/transferencia
curl -s -w ' %{http_code}\n' -X POST -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"cuentaOrigen":103,"cuentaDestino":999,"monto":30}' http://servicio-pagos:8086/api/pagos/transferencia

# Retiro y consultas
curl -s -X POST -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"cuentaId":105,"monto":20}' http://servicio-pagos:8086/api/pagos/retiro
curl -s -H "$AUTH" "http://servicio-pagos:8086/api/pagos?cuentaId=103"
curl -s -H "$AUTH" http://servicio-pagos:8086/api/pagos/1
```

El header `Idempotency-Key` es opcional; sin él, cada solicitud crea un pago nuevo.

### Clientes y cuentas

```sh
curl -s -H "$AUTH" http://servicio-clientes:8085/api/clientes
curl -s -X POST -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"rut":"44444444-4","nombre":"Pedro","apellido":"Diaz","email":"pedro.diaz@correo.cl"}' \
  http://servicio-clientes:8085/api/clientes
curl -s -X PATCH -H "$AUTH" -H "Content-Type: application/json" -d '{"perfil":"PREMIUM"}' \
  http://servicio-clientes:8085/api/clientes/1/perfil

# Apertura de cuenta (valida que el cliente exista y este ACTIVO), consulta y cierre (exige saldo 0)
curl -s -X POST -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"clienteId":1,"nombre":"Ana Perez","edad":30,"tipo":"ahorro","saldoInicial":0}' \
  http://servicio-cuentas:8080/api/core/cuentas
curl -s -H "$AUTH" "http://servicio-cuentas:8080/api/core/cuentas?clienteId=1"
curl -s -H "$AUTH" http://servicio-cuentas:8080/api/core/cuentas/108
```

El alta de cliente responde `201`; si ese RUT o email ya existe, `409` (por ejemplo al repetir el comando: usar otro RUT).

El cierre es `POST /api/core/cuentas/{cuentaId}/cierre` y el mantenimiento es `PUT /api/core/cuentas/{cuentaId}` con `{"nombre","edad","tipo"}`.

### Auditoría

```sh
curl -s -H "$AUTH" http://servicio-auditoria:8084/api/auditoria/resumen
curl -s -H "$AUTH" "http://servicio-auditoria:8084/api/auditoria/eventos?topico=transacciones-completadas"
curl -s -H "$AUTH" "http://servicio-auditoria:8084/api/auditoria/eventos?tipo=MONTO_ELEVADO"
```

`/eventos` devuelve como máximo los 100 eventos más recientes. Los tópicos se listan con `docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list`.

### Disparar un job batch

Los jobs `interesesJob`, `transaccionesJob` y `cuentasAnualesJob` no corren al arrancar. Se disparan por endpoint y la fecha identifica la ejecución:

```sh
curl -s -w ' %{http_code}\n' -X POST -H "$AUTH" "http://servicio-cuentas:8080/api/core/batch/interesesJob?fecha=2026-10-11"
# Repetir con la misma fecha: 409 "ya se ejecuto" (una sola ejecucion por job y fecha, aun con varias replicas)
curl -s -w ' %{http_code}\n' -X POST -H "$AUTH" "http://servicio-cuentas:8080/api/core/batch/interesesJob?fecha=2026-10-11"
```

### Circuit breakers

`/actuator/circuitbreakers` de `servicio-pagos` y `servicio-cuentas` exige el token interno:

```sh
curl -s -H "$AUTH" http://servicio-pagos:8086/actuator/circuitbreakers
```

En los BFF exige el token de su canal, por ejemplo `https://localhost:8083/actuator/circuitbreakers` con el token del cajero.

## 7. Datos de prueba

Las cuentas iniciales de `01-datos.sql` son `101`, `102`, `103`, `105`, `107` y `108`; la `101` tiene saldo 0. `servicio-clientes` crea 3 clientes de ejemplo (ids 1, 2 y 3) si su tabla está vacía. La base se consulta en `localhost:3307`, o con:

```powershell
docker compose exec mysql mysql -uroot -p banco_xyz
```

## 8. Resiliencia y escalado: qué observar

- **Circuit breaker:** con `docker compose stop servicio-cuentas`, los depósitos responden `503` y el pago queda FALLIDO. Tras pocas fallas el circuito de pagos pasa a `OPEN` y responde de inmediato. Con `docker compose start servicio-cuentas`, se cierra solo.
- **Réplica caída:** con 2 réplicas, `docker stop <contenedor>` de una de ellas no corta el servicio:
  - nginx deja de usarla en unos 5 s;
  - los clientes Eureka, en unos 5-10 s (registro y caché del LoadBalancer refrescan cada 5 s).
- **Pagos inciertos:** si cuentas no responde durante un pago, el reconciliador de pagos lo cierra en los segundos siguientes; queda la marca "Reconciliado: ..." en el `motivo` del pago.

## 9. Resolución de problemas

| Síntoma | Causa y solución |
|---|---|
| `mvnw` falla con errores de compilación o de versión de Java | `JAVA_HOME` no apunta a un JDK 17. Configurarlo como en la sección 2 y verificar con `.\mvnw.cmd -v`. |
| Falla `BatchApplicationTests` | Necesita el Config Server y MySQL locales (sección 2). Excluirlo con `-Dtest=!BatchApplicationTests`. |
| Faltan bases o tablas (`banco_clientes`, `BATCH_JOB_INSTANCE`...) | Los scripts de `db/init/` solo se ejecutan con el volumen de MySQL vacío. Con un volumen anterior: `docker compose down -v` y volver a levantar (se pierden los datos). |
| Un BFF responde `503` justo después de levantar o reiniciar un servicio | Ventana de Eureka: el servicio recién iniciado tarda unos segundos en registrarse y en que los clientes refresquen su caché. Esperar 30 s y reintentar. |
| `401` con un token que antes funcionaba | `auth-server` genera su clave RSA en memoria: al reiniciarlo, los tokens anteriores dejan de validar. Pedir un token nuevo (los servicios renuevan el suyo solos). |
| `400` por JSON mal formado al llamar desde PowerShell | PowerShell 5.1 quita las comillas del JSON al llamar a `curl.exe`: escaparlas con `\"` como en los ejemplos, o usar bash. |
| Contenedores `unhealthy` o reiniciándose | Falta de memoria en Docker: asignar 8 GB o más, sobre todo para el despliegue escalado. Revisar `docker compose logs <servicio>`. |
| Puerto ocupado al levantar | Liberar 3307, 8081-8083, 8761, 8888 o 9000. |
