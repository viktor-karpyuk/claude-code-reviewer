# Changelog

Reglas de numeración en [CLAUDE.md](CLAUDE.md): un requerimiento **nuevo** incrementa *major*;
cambiar uno **existente** incrementa *patch*.

## 8.0.0

Requerimiento nuevo: **las notas propias aparecen en la vista de Review**.

- Los comentarios que dejás desde la vista de código ahora figuran en el resumen de la pestaña
  Review, junto a los hallazgos automáticos, con su archivo y línea y una marca de si ya se
  publicaron. Antes vivían sólo en la vista de código, así que desde Review no había forma de
  saber que quedaban pendientes.
- "Publicar inline" los incluye en la misma tanda: para el PR son comentarios anclados iguales, y
  separarlos obligaba a ir a otra pestaña a terminar de publicar lo mismo. El contador de
  pendientes ya cuenta los dos.

Arreglo:

- **Las dos vistas de un PR miraban reviews distintas.** La pestaña Review tomaba la última
  corrida *terminada* y la de Código la última de *cualquier estado*. Con un reintento fallido
  encima —el estado en que estaban tres PRs de la base— la vista de código se quedaba sin
  hallazgos mientras Review mostraba los de la última buena, y lo publicado desde una no se
  reflejaba en la otra. Ahora las dos resuelven con el mismo criterio.

## 7.0.0

Requerimiento nuevo: **estado del PR y acceso al histórico**.

- Cada fila muestra si el PR está abierto, mergeado o cerrado. Se ve siempre, no sólo al buscar
  histórico: un PR que se mergeó mientras estaba en pantalla ya no hay que revisarlo.
- Filtro por estado, y los mergeados y cerrados **no se cargan por defecto**. Se traen apretando
  "Buscar histórico", que es la acción explícita. En `kubrik-erp-be` son 148 históricos contra 4
  abiertos: cargarlos siempre sería descargar cientos de resultados para mostrar los cuatro que
  importan.
- La búsqueda de históricos va por un camino aparte y no toca el caché de abiertos, así que no
  ensucia ni enlentece la vista de todos los días. Trae hasta 3 páginas por vez.
- Un estado desconocido —Bitbucket tiene `SUPERSEDED`, que no modelamos— cuenta como cerrado y no
  como abierto: darlo por abierto lo metería en la lista de trabajo pendiente.
- GitHub no distingue mergeado de cerrado en el filtro de la API; se separa por `merged_at`, que
  es el único dato que lo dice.

## 6.0.0

Requerimiento nuevo: **recorrer los hallazgos en la vista de código**.

- La columna izquierda tiene ahora dos pestañas: «Archivos» y «Para revisar». La segunda lista
  todos los hallazgos de la review y las notas propias **agrupados por archivo**, con su línea, su
  severidad y si ya se publicaron.
- Botones «‹ Anterior» y «Siguiente ›» para ir de uno en uno: cambia de archivo solo, baja el diff
  hasta la línea —con unas líneas de contexto arriba— y la resalta. Arriba se lee "3 de 7" con el
  archivo y la línea actual.
- El orden es el de lectura: archivo del diff y, dentro de cada uno, por línea. El orden en que la
  review devolvió los hallazgos no le sirve a nadie. Hallazgos y notas van intercalados; un
  hallazgo sin línea se lee al final de su archivo, y uno cuyo archivo ya no está en el diff va al
  final de todo en vez de descolocar el recorrido.
- El paso a paso es estable: dos observaciones en la misma línea no se intercambian entre
  recomposiciones, porque si no el "3 de 7" señalaría cosas distintas cada vez.

Rediseño:

- **El loading de la review deja de ser un muro de log a pantalla completa.** Ahora es una tarjeta
  compacta: spinner, profundidad y modelo, tiempo transcurrido, una barra fina y el paso actual en
  una línea. El detalle completo sigue estando, plegado detrás de «Ver detalle». Y como ya no
  ocupa la pantalla, la review anterior se sigue leyendo mientras corre la nueva.

## 5.0.1

- El caché de PRs se rellena con la fecha de apertura en vez de quedarse vacío: con un ETag
  vigente el proveedor contesta 304 y la copia guardada nunca se completaba, así que el orden por
  antigüedad no tenía con qué ordenar. Cuando al caché le falta un dato que ahora se usa, se pide
  el cuerpo entero una vez.

## 5.0.0

Requerimiento nuevo: **ordenar la lista de pull requests**.

- Por defecto, los más viejos primero: es lo primero que hay que revisar. Los proveedores
  devuelven la lista por actividad reciente, que es justo el orden inverso — en `kubrik-erp-be`
  el #148, abierto el 3 de agosto, aparecía último y el #152, de hoy, primero.
- Se puede reordenar: más viejos, más nuevos, sin actividad hace más tiempo, o actividad más
  reciente. La elección se guarda y sobrevive al reinicio.
- Cada fila muestra cuándo se abrió el PR además de la última actividad, para que el orden se
  entienda. Antes sólo se guardaba la fecha de actualización; ahora también la de creación.
- Un PR cacheado sin fecha va al final en cualquier orden: vacío significa "no sé", no "año
  cero", y encabezar la lista de "más viejos" con lo que menos se sabe sería lo peor.

Arreglos:

- **Las herramientas denegadas ya se guardan.** La columna existía desde la v11 pero nunca se
  escribía: el aviso de "esta review corrió con N herramientas denegadas" vivía sólo en el feed en
  vivo y se perdía al cerrar la pantalla, así que no había forma de saber qué comando permitir.
  Y ahora se guarda con el comando, no sólo "Bash".
- **Los tokens consumidos ya se guardan.** `finish()` se llamaba con cuatro argumentos y los
  contadores quedaban en su valor por defecto: 14 reviews terminadas y US$ 29 de consumo
  figuraban con 0 tokens en el panel y en Info.

## 4.0.0

Requerimiento nuevo: **procesar varias respuestas a la vez**.

- Se pueden analizar todas las respuestas pendientes de un PR de una sola vez, con un botón, en
  vez de ir de a una esperando cada redacción. Cada tarjeta muestra su propio estado.
- El motor las encola de a tres: lanzar diez juntas abriría diez subprocesos de Claude Code
  compitiendo por CPU y por el límite de la cuenta, y tardaría más que en tanda. El resto arranca
  solo a medida que se libera un lugar. Verificado con un test que mide el solapamiento real.

Arreglos:

- **Publicar una respuesta ya figura como que contestamos.** Al sincronizar el hilo sólo se
  pasaban como nuestros los ids del comentario resumen, así que los hallazgos publicados inline y
  nuestras propias respuestas se guardaban como comentarios ajenos: el historial no los marcaba
  como nuestros. Una migración corrige el hilo ya guardado.
- **Una respuesta a una contestación nuestra ahora se detecta.** Como el id de nuestra respuesta
  no figuraba como nuestro, la segunda vuelta de la conversación no se veía nunca.
- La lista de PRs muestra "contestada": al publicar la última respuesta, el PR volvía a mostrarse
  como si nada hubiera pasado.

## 3.0.5

- **Publicar los hallazgos ya no deja el PR como "lista para publicar".** `published_url` sólo se
  escribía al publicar el comentario resumen, así que el camino normal —publicar cada hallazgo
  anclado a su archivo y línea— nunca marcaba la review: el PR quedaba pendiente para siempre en
  la lista, en el panel y en el contador de la barra de menú. Ahora, al publicar el último
  hallazgo que quedaba, la review pasa a publicada. Una migración corrige las que ya estaban así.
- La lista distingue "publicada a medias (2 de 3)" de "lista para publicar", que antes se veían
  igual.
- Un hallazgo publicado sin link cuenta igual como publicado: antes, condicionar por la URL
  dejaba esas reviews colgadas como pendientes.
- **Una review que no abrió el diff ya no se guarda como terminada.** El modelo a veces contesta
  "no puedo acceder al diff por permisos" sin haberlo intentado —pasó en el PR #151, con cero
  herramientas denegadas—; esa disculpa se guardaba como review buena, publicable y contada como
  trabajo hecho. Ahora la corrida se marca fallida y se puede reintentar. El prompt además aclara
  que los comandos de git de sólo lectura ya están autorizados.
- **Los 401 de Bitbucket se reintentan rápido.** El backoff arrancaba en 1s y escalaba a 16s, y
  colgaba la interfaz casi un minuto antes de rendirse. Medido: cerca del 40% de los pedidos
  falla en el borde de Atlassian sin llegar a validar el token, y el reintento inmediato pasa.
  Ahora empieza en 250ms, hace más intentos y conserva la cola exponencial para el bloqueo por
  frecuencia, que también existe. El mensaje de error ya no afirma que sea límite de tasa.

## 3.0.4

- Conectar y editar un repositorio deja de ser un modal y pasa a ser una pantalla propia. Eran
  quince campos de cuatro temas distintos metidos en una caja de 520dp, con scroll interno y el
  botón de guardar abajo de todo.
- El formulario queda agrupado en cuatro bloques —conexión, cómo se revisa, revisión automática
  y respuestas—, en dos columnas cuando la ventana da el ancho, con la barra de acciones fija
  abajo para que "Guardar" no dependa del scroll.
- La ruta local avisa cuando no hay un `.git` ahí, en vez de sólo pintarse de rojo; y al editar
  se explica por qué el proveedor y las coordenadas están bloqueados.
- Los textos del formulario que todavía estaban escritos a mano en español pasan a las tablas de
  idioma: la pantalla ahora se traduce entera.
- La confirmación de borrado sigue siendo un modal, que es donde corresponde: interrumpe a
  propósito porque no tiene vuelta atrás.

## 3.0.3

- El panel scrollea completo en ventanas chicas: el encabezado (título, tarjetas, consumo y
  estado del automático) estaba fijo arriba de la lista y en poca altura tapaba todo lo demás
  sin dejar llegar a ello.
- Las tarjetas de estado y las de período se acomodan en varias filas cuando la ventana es
  angosta, en vez de quedar cortadas a la derecha.

## 3.0.2

- Ajustes ahora scrollea: con todas las secciones el contenido pasaba el alto de la ventana y lo
  de abajo —permisos del subproceso— no se podía ver.

## 3.0.1

- La barra lateral pasa a dos filas de dos: al sumar "Info" quedaron cuatro items en 260dp y
  "Ajustes" quedaba recortado y difícil de tocar.

## 3.0.0

Requerimiento nuevo: **pantalla de información de la aplicación**.

- Nueva sección "Info" en la navegación: versión, licencia, idioma, versión y ruta del CLI de
  Claude Code, sistema y Java, ubicación y versión de esquema de la base, clave maestra,
  estadísticas de uso y consumo, y el detalle de cada repositorio conectado.
- Todo el contenido es seleccionable y hay un botón que copia el diagnóstico completo como texto,
  que es lo que hace falta cuando algo falla.
- Se expone la versión de esquema y la ruta de la base desde `Store`, que antes no se podían
  consultar desde la UI.

## 2.0.0

Requerimiento nuevo: **gestión de versiones**.

- La versión pasa a tener una sola fuente de verdad (`version` en `build.gradle.kts`); el
  `packageVersion` del instalador se deriva de ahí en vez de estar escrito aparte, donde ya
  podían divergir.
- Gradle genera un recurso con la versión y la app la lee en runtime; se muestra en Ajustes.
- Se documenta la regla de versionado en `CLAUDE.md` y se abre este changelog.

## 1.0.0

Primera versión estable, con todo lo construido hasta acá:

- Conexión de repositorios de Bitbucket y GitHub, con token cifrado (AES-256-GCM) y clave fuera
  de la base.
- Reviews con la consola local de Claude Code: sin API key, con progreso en vivo y cancelación.
- Tres niveles de profundidad y cinco tipos de proyecto, ambos con modo automático que los
  infiere del diff.
- Modelo elegible, descubierto del propio CLI en vez de una lista fija.
- Hallazgos estructurados anclados a archivo y línea, publicables como comentarios inline.
- Visor de código con diff y notas locales; visor de commits.
- Historial de reviews, publicaciones y del hilo del PR, con filtro por autor.
- Revisión automática por repositorio, con reglas de qué saltear y tope de gasto por ciclo.
- Detección de respuestas del desarrollador y redacción de contestación, con modo configurable
  (sólo detectar / preparar y avisar / contestar automáticamente).
- Panel con período en curso, histórico, listas accionables y consumo.
- Multi-idioma (español e inglés) con fallback al idioma base.
- Icono en la barra de menú de macOS y opción de seguir en segundo plano al cerrar.
- Notificaciones del sistema al aparecer un PR nuevo, terminar una review o recibir una respuesta.
- Caché persistente de PRs con revalidación por ETag.
