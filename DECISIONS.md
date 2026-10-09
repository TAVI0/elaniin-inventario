# Decisiones

## Supuestos

Puntos que el contrato o el README no definen del todo y cómo los resolví.

- **`reserve` es idempotente por `orderId`.** El README dice que la app reenvía el pedido hasta recibir respuesta, así que el mismo `orderId` puede llegar varias veces. Si el pedido ya tiene una reserva activa o confirmada, se devuelve esa misma reserva (mismo `expiresAt`) sin apartar unidades de nuevo, aunque el stock se haya agotado mientras tanto. Si llega el mismo `orderId` con otro SKU u otra cantidad, no es un reintento sino un error del cliente, y se rechaza con `IllegalStateException`.
- **Una reserva vencida no se puede retomar.** Si el pedido llega otra vez después de que su reserva expiró, se trata como un pedido nuevo y se vuelven a validar límite y stock. `confirm` sobre una reserva vencida lanza `IllegalStateException`, porque ya no está activa. Esto tiene una consecuencia de negocio: si un pago se aprueba después del vencimiento (algo esperable en `PRE_ORDER`, que se paga por transferencia), el cliente pagó y no tiene unidades. El servicio no puede resolverlo solo, porque esas unidades quizás ya se vendieron a otro cliente. El flujo de pagos tiene que manejar ese error: intentar reservar de nuevo con el mismo pedido y, si no hay stock, reembolsar.
- **El vencimiento es en el instante exacto de `expiresAt`.** A los 15 minutos justos de una reserva `STANDARD` el tiempo para pagar terminó y las unidades vuelven al stock.
- **Confirmar dos veces lanza `IllegalStateException`.** El contrato pide esa excepción cuando el pedido no tiene una reserva activa, y una reserva confirmada ya no lo está. Las unidades no se descuentan dos veces.
- **El aviso de stock bajo se rearma solo con un reabastecimiento (`addStock`).** El README pide no repetir el aviso "mientras el producto no se reabastezca". Que venzan reservas sube el disponible pero no es un reabastecimiento, así que no genera un aviso nuevo. Después de un `addStock`, el aviso puede volver a salir aunque el producto siga con 5 unidades o menos, porque compras tiene que saber que la reposición no alcanzó.
- **El aviso se evalúa al reservar.** Es la única operación que baja el disponible: confirmar no lo cambia (las unidades ya estaban apartadas) y `addStock` lo sube.
- **Registrar un SKU que ya existe solo cambia su categoría.** Conserva el stock y las reservas. Las reservas ya hechas mantienen su vencimiento original y las nuevas siguen las reglas de la nueva categoría. Reemplazar el producto habría dejado el stock en 0 con reservas activas, o sea disponible negativo.
- **El límite por pedido se valida antes que el stock.** Así un pedido que excede el límite de `FLASH_SALE` recibe siempre `OrderLimitExceededException`, sin importar cuánto stock haya. Es el mismo orden en que el contrato documenta las excepciones.
- **El stock de un producto no puede superar `Integer.MAX_VALUE`.** Un `addStock` que lo desbordaría lanza `IllegalArgumentException` y no modifica nada, en vez de dejar el stock en negativo sin aviso.
- **Los argumentos nulos lanzan `NullPointerException`**, siguiendo la convención de Java (`Objects.requireNonNull`), porque el contrato no define ese caso.

## Diseño

- **Las reglas de cada categoría son una tabla de datos (`CategoryPolicies`).** Tiempo para pagar y límite por pedido son datos, no comportamiento distinto. Sumar la categoría de la próxima temporada es agregar una línea en la tabla. La tabla se construye y se valida en `Inventory.create`, y se le inyecta al servicio: si alguien agrega el valor al enum y se olvida de la tabla, el servicio no se puede crear y falla con un mensaje que nombra la categoría faltante, en vez de fallar con el primer cliente que reserva. `CategoryPoliciesTest` cubre ese caso y verifica la tabla del README. Inyectarla también deja preparado el paso a reglas cargadas desde configuración.
- **Vencimiento lazy en vez de un proceso en segundo plano.** Antes de cada operación se descartan las reservas cuyo `expiresAt` ya pasó según el `Clock` inyectado. No hay hilos ni timers que sincronizar, el comportamiento es determinístico y se testea moviendo un reloj, sin `sleep`. Además, es el mismo modelo que se usa con una base de datos (`WHERE expires_at > now`), así que la migración no cambia la semántica.
- **Un único lock por instancia.** Reservar lee stock y reservas y escribe una reserva nueva; confirmar descuenta stock y cierra la reserva. Son operaciones de varios pasos sobre varios mapas que tienen que ser atómicas en conjunto. Un lock por SKU reduciría contención, pero agrega complejidad sin un cuello de botella medido.
- **El disponible se calcula en cada consulta, no se guarda.** Cada operación recorre las reservas activas de la tienda para descartar las vencidas y sumar las del SKU. Es un costo lineal sobre las reservas activas, pero en memoria son microsegundos aun con miles de reservas, y a cambio el disponible nunca puede desincronizarse porque siempre sale de la fuente de verdad. Si una prueba de carga lo marca como cuello de botella, la alternativa es llevar un contador de unidades reservadas en cada producto y una cola de reservas ordenada por vencimiento: cada operación pasa a costar casi lo mismo sin importar cuántas reservas haya, a cambio de mantener ese contador sincronizado al reservar, confirmar y vencer.
- **El aviso a compras se envía fuera del lock y sus fallas no afectan la reserva.** Hoy los avisos salen por correo, que es I/O lento: dentro del lock, un mail lento frenaría todas las reservas de la tienda. La decisión de avisar se toma bajo el lock (para no duplicarla entre hilos) y el envío ocurre después. Si el canal falla, se registra el error y la reserva del cliente sigue en pie.
- **Paquetes por responsabilidad.** `impl` orquesta, `store` guarda el estado y sabe cómo vencen las reservas, `policy` tiene las reglas por categoría y `alert` decide y envía los avisos. Cuando cambie una regla de negocio, hay un solo lugar donde buscarla.

## Qué quedó afuera

- Coordinación entre varias instancias: todo vive en memoria de un único proceso.
- Persistencia: un reinicio pierde stock y reservas.
- Más canales de aviso. `StockAlertListener` ya es el punto de extensión: sumar SMS o Slack es pasar un listener compuesto que reenvíe a cada canal, sin tocar el servicio.
- Cancelación explícita de una reserva: el README solo pide liberarla por vencimiento.
- Limpieza de reservas confirmadas: se guardan para responder reintentos y en memoria crecen sin límite.
- Métricas y trazas.

## Antes de llevarlo a producción

- **Base de datos como fuente de verdad.** Tablas `products` y `reservations` con `order_id` único (la idempotencia la garantiza la base, no la memoria de una instancia) y `expires_at` indexado. Reservar sería una transacción que bloquea la fila del producto (`SELECT ... FOR UPDATE`) o un `UPDATE` condicional con control optimista por versión. Eso reemplaza el lock en memoria, que no protege nada entre instancias.
- **Avisos con outbox.** Guardar el aviso en la misma transacción que la reserva y enviarlo con un proceso aparte, con reintentos. Hoy, si el correo falla, el aviso se pierde (queda en el log).
- **Reglas por categoría como configuración**, para que marketing pueda sumar una categoría sin un deploy. Requiere que el contrato deje de usar un enum, así que hay que acordarlo con quienes consumen el API.
- **Política de retención** para reservas confirmadas y vencidas (purga o archivo).
- **Logs estructurados y métricas**: reservas creadas, vencidas y confirmadas, rechazos por stock o límite y avisos enviados o fallidos.
- **Prueba de carga** con tráfico de temporada alta, para medir el lock único y el cálculo del disponible antes de decidir si hace falta algo más fino.
