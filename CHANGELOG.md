# Changelog

Reglas de numeración en [CLAUDE.md](CLAUDE.md): un requerimiento **nuevo** incrementa *major*;
cambiar uno **existente** incrementa *patch*.

## 22.0.4

- En el resumen de hallazgos de la review, el `archivo:línea` de cada uno es ahora un **link al
  código**: abre la pestaña Código en ese archivo, baja hasta la línea y la resalta. Leer un
  hallazgo sin poder ver la línea que señala obligaba a buscarla a mano en la otra pestaña. Es el
  mismo salto que ya tenía la conversación.
- El estado de verificación de cada hallazgo pasa a tener **ícono además de color**: tilde para
  corregido, advertencia para a medias, cruz para sin corregir, e información para sin verificar.
- Arreglo de iconografía: "sin verificar" llevaba un **✓**, el mismo símbolo que "corregido", así
  que se leía como hecho cuando en realidad nadie lo había mirado todavía. Ahora el ícono es
  neutro: ni éxito ni fracaso.

## 22.0.3

- Lo que ya está cerrado —publicado, resuelto, listo para mergear— se muestra en **verde y dentro
  de un recuadro**, no en el mismo azul que el resto del texto. Un estado terminal escrito igual
  que todo lo demás se pierde entre el texto; el recuadro lo separa y el verde dice "cerrado" sin
  leerlo. Vale en la lista de PRs, la conversación, el resumen de la review, el visor de código y
  el panel.
- Lo que todavía espera algo se sigue leyendo como texto: el recuadro tiene que significar algo.
- **Más contraste en el tema oscuro.** Los tres niveles —fondo, superficie y superficie variante—
  estaban muy juntos y muy abajo: las tarjetas no se despegaban del fondo y todo se leía como una
  masa oscura. Ahora cada nivel sube un escalón visible respecto del anterior.
- El texto secundario —autores, fechas, rutas de archivo— pasa de `#B4BDCE` a `#C5CEDE`: estaba al
  borde de lo legible y es justo donde vive la información de los PRs.
- Se definen `outline` y `outlineVariant` en los dos temas, que hasta ahora quedaban en el default
  de Material y por eso los separadores casi no se veían.
- **El diff se lee mejor**: las líneas agregadas y borradas tenían tan poca opacidad que sobre el
  fondo oscuro quedaban barrosas y casi del mismo tono. Y el código pasa de 12 a 13 puntos.

## 22.0.2

- Los comentarios del desarrollador se distinguen de los nuestros de un vistazo: cada mensaje va
  como burbuja con **fondo propio y una barra de color a la izquierda** —el color del tema para lo
  nuestro, violeta para lo suyo—. Antes lo único distinto era el color del nombre, tres palabras
  arriba de un bloque de texto idéntico, y en una discusión larga había que subir la vista para
  saber quién estaba hablando.
- Vale igual en la conversación y en el historial, con la misma definición: la barra de color es lo
  que se ve de reojo al scrollear, y el fondo diluido separa un mensaje del siguiente sin líneas.
- El ámbar del historial se retiró de los comentarios: quedó reservado para la gravedad, que es
  otra cosa y se estaba pisando.
- Las reviews y las notas locales se siguen leyendo como texto: no son de nadie en ese sentido.

## 22.0.1

- Volver desde un PR te lleva **a la pantalla de la que viniste**, no siempre a la lista del
  repositorio. Si entraste desde el panel, volvés al panel: aterrizar en la lista te dejaba en un
  lugar en el que nunca estuviste y perdías lo que estabas revisando.
- Cuando no hay una pantalla anterior útil —recién abierta la app, o entrando desde el icono de la
  barra de menú— se usa la lista del repositorio, que es el destino sensato.
- Arreglo en el camino: al **entrar** al formulario de repositorio no se registraba de dónde venías,
  así que "Cancelar" caía en la bienvenida. Sólo debía conservarse el origen al saltar de un
  formulario a otro, no al abrir el primero.

## 22.0.0

Requerimiento nuevo: **paneles redimensionables**.

- Las líneas que separan los paneles se pueden arrastrar: la lista de archivos contra el diff en la
  vista de código, la de commits contra su detalle, y la barra lateral contra el contenido.
- La zona sensible del arrastre es de 8 píxeles aunque la línea que se ve mida uno: acertarle a un
  pelo de un píxel con el mouse es puntería, no una interacción. El cursor cambia al pasar por
  encima y la línea se resalta mientras arrastrás.
- El ancho se guarda y sobrevive al reinicio. Acomodar los paneles depende del monitor y de qué
  estés mirando; volver a arrastrarlos en cada arranque sería tratar esa decisión como un capricho
  del momento.
- Hay topes mínimo y máximo: sin ellos se puede dejar un panel en cero y ahí desaparece el borde
  del que hay que tirar para recuperarlo. Un valor corrupto en las preferencias vuelve al ancho por
  defecto en vez de arrancar con un panel invisible.
- La posición se guarda al soltar, no en cada píxel: serían cientos de escrituras por arrastre
  sobre la misma conexión que usa el resto de la app.

## 21.0.0

Requerimiento nuevo: **el panel agrupa las respuestas por PR** y **el historial es una línea de
tiempo**.

- "Te respondieron" muestra **un renglón por PR** con su número de respuestas pendientes, en vez de
  una lista plana de todas. Con 34 respuestas sueltas, contestar dos dejaba una lista de 32
  visualmente idéntica: el contador bajaba pero no se notaba. Ahora se lee "kubrik-erp-be #149 · 14
  respuestas por contestar" y al contestar dos dice 12.
- El encabezado dice cuántos PR y cuántas respuestas en total, y cada renglón si están todas
  redactadas o cuántas van.
- El **historial** pasa a ser una sola línea de tiempo agrupada por día, en vez de cuatro listas
  separadas —reviews, publicaciones, hilo y notas— cada una con su propio orden. Reconstruir qué
  pasó y cuándo obligaba a saltar entre secciones comparando fechas a ojo.
- Cada evento dice de qué tipo es y de quién, con el filtro por autor conservado.
- Un comentario nuestro ya publicado deja de aparecer dos veces: la publicación y el comentario del
  hilo son la misma cosa vista dos veces, y duplicarlos hacía parecer que habíamos comentado el
  doble. Una publicación que todavía no se sincronizó sí se muestra, para no esconder algo que sí
  se publicó.

## 20.0.0

Requerimientos nuevos: **porcentaje de listo para mergear**, **pasada final** y **commits desde la
review**.

- La pantalla del PR muestra cuán listo está, de 0 a 100, **y de qué está compuesto**: debajo del
  número va lo que falta, ordenado por cuánto pesa. Un porcentaje que no se puede desarmar en
  "esto sí, esto no" es decorativo, y peor si de él depende mergear.
- Los pesos dicen qué importa más: un comentario publicado sin resolver pesa el triple que uno que
  ni se publicó —el primero es una objeción viva, el segundo una decisión que todavía no tomaste—,
  y diez objeciones abiertas no valen lo mismo que una.
- Nunca muestra 100% con algo pendiente: con muchos ítems el redondeo podía llegar ahí, y esa
  mentira rompería la confianza en el número.
- Sin review el porcentaje es 0, que significa "no hay información", no "está muy lejos".
- **Pasada final**: una última mirada al código completo antes de mergear, distinta de la
  verificación. Aquélla pregunta "¿arreglaron lo que dije?"; ésta, "¿hay algo que no deba entrar a
  la rama destino?". Se le pasa lo ya discutido para que no lo repita, y se le pide explícitamente
  que devuelva vacío cuando no encuentra nada: una pasada que siempre encuentra algo no sirve para
  decidir.
- La pasada final vale sólo para el commit sobre el que corrió: si el PR avanza, vuelve a faltar.
- La pestaña **Commits** dice el total y cuántos llegaron **después de la review**, marcándolos.
  Si el commit revisado ya no está en la rama —un rebase lo reescribe— lo dice en vez de reportar
  cero, que haría creer que nadie tocó nada.

## 19.0.2

- La gravedad de cada hallazgo se distingue de verdad: **bloqueante en rojo, importante en ámbar,
  menor en azul**. Antes `major` usaba el azul del tema y `minor` un gris, que al lado se leían
  igual; el ámbar aparecía suelto en el visor de código sin significar nada en particular.
- Los tres colores son de familias distintas y no tonos del mismo, para que la diferencia se lea
  de reojo.
- Además del color, cada nivel lleva su marca —`●●●`, `●●`, `●`— y su etiqueta traducida. El color
  solo no le sirve a quien no distingue rojo de ámbar, ni sobrevive a una captura en blanco y negro.
- La severidad ahora también se muestra en la **conversación**, donde no aparecía: era la única
  vista donde no se sabía si lo que estabas leyendo frenaba el merge o era un detalle.
- El contador de hallazgos de cada archivo toma el color del **peor** que tenga, en vez de un ámbar
  fijo para todos.
- Los cuatro lugares donde se muestra la gravedad usan ahora la misma definición; antes cada uno
  elegía su color por su cuenta.

## 19.0.1

- Publicar un comentario desde la conversación ya no apaga los botones de los demás. Había un solo
  flag de "publicando" para toda la pantalla, así que apretar uno deshabilitaba el resto —y un
  botón deshabilitado de Material tiene tan poco contraste que se lee como desaparecido. Ahora el
  estado es por comentario, el botón dice "Publicando…" y hay un indicador al lado, así se ve cuál
  está trabajando.
- Al publicar, la tarjeta baja en la lista en vez de subir. "Sin publicar" ahora ordena antes que
  "sin verificar": publicar depende sólo de vos, verificar espera a que el otro suba algo. Antes la
  tarjeta recién publicada saltaba por encima de las que faltaban y las corría mientras seguías
  clickeando.

## 19.0.0

Requerimientos nuevos: **"esperando respuesta de ellos"** y **cerrar un hilo sin esperar cambios**.

- El panel separa dos cosas que estaban juntas: **"Te respondieron"** (falta que contestes vos) y
  **"Esperando respuesta de ellos"** (contestaste todo y la pelota está del otro lado). Un PR donde
  ya habías contestado seguía apareciendo como si te tocara mover algo.
- Un hilo se puede **cerrar sin esperar cambios**: es el caso del comentario que sólo validaba una
  respuesta, o que quedó saldado hablando. Antes esos quedaban esperando una corrección que nunca
  iba a llegar, y el PR no podía darse por listo. Se puede reabrir.
- Es distinto de descartar: descartar es "decidí no publicarlo"; cerrar es "se publicó, se habló y
  quedó saldado". Ninguno de los dos borra nada.
- Un hilo cerrado así deja de pedir seguimiento y deja de contar como "sin verificar" para el
  merge, que era la incoherencia que lo hacía inútil.

## 18.0.0

Requerimiento nuevo: **seguimiento de comentarios sin respuesta**.

- Cada hilo muestra hace cuántos días espera respuesta, y pasado el plazo —tres días por defecto—
  aparece "Hacer seguimiento": publica un recordatorio colgado de nuestro propio comentario.
- El mensaje sale de una plantilla **traducida al idioma elegido** y se muestra editable antes de
  mandarlo. No lo redacta el modelo: pagar una corrida para escribir "¿lo podés mirar?" no tiene
  sentido, y un recordatorio generado suena peor que uno escrito.
- **La app nunca lo manda sola.** Se publica en el PR de otra persona; insistir automáticamente
  sería el tipo de cosa que uno no quiere descubrir después.
- El reloj se reinicia al mandar un recordatorio: sin eso, la app ofrecería insistir todos los
  días sobre algo que ya insististe ayer.
- Si lo último del hilo es de ellos, no se ofrece recordar nada: ahí quien debe una respuesta
  somos nosotros, y recordarles sería exactamente al revés.

## 17.0.1

- El salto de la conversación al código era de ida: la única vuelta era la pestaña, y te dejaba al
  principio de la lista buscando de nuevo dónde estabas. Ahora hay un "← Volver a la conversación"
  que aparece sólo cuando llegaste por un salto, y que baja la lista hasta la tarjeta de la que
  saliste.
- Si te vas del código por tu cuenta —tocando otra pestaña— el "volver" desaparece: apuntaría a un
  hilo del que ya saliste.

## 17.0.0

Requerimiento nuevo: **ver el código desde la conversación**.

- El `archivo:línea` de cada hilo es un link, y hay un botón "Ver el código": abre la pestaña
  Código en ese archivo, baja hasta la línea y la resalta. Leer la discusión sin el código al lado
  obligaba a buscarlo a mano en la otra pestaña.
- El salto además mueve el cursor del recorrido al hallazgo correspondiente, así "Siguiente"
  continúa desde donde estabas mirando y no desde el principio.
- El pedido de salto se limpia al aplicarse: si no, volver a la pestaña Código saltaría otra vez
  al mismo lugar y perdería dónde estabas leyendo.
- Una pregunta todavía sin publicar ahora se puede **publicar o descartar desde la propia
  conversación**, que es donde uno se da cuenta de que faltaba. Publicar una respuesta ya
  redactada ya se podía desde la tarjeta, y sigue igual.

Arreglo:

- Un borrador de respuesta preparado para una respuesta **anterior** del hilo no se veía: sólo se
  miraba el de la última. Existía en la base y no aparecía en ningún lado.

## 16.0.0

Requerimientos nuevos: **la conversación por pregunta** y **el atributo "listo para mergear"**.

- Nueva pestaña **Conversación**, que reemplaza a Respuestas. Cada pregunta es una tarjeta con su
  historia completa: qué señalamos, qué contestaron, qué redactó la IA para responder, el veredicto
  sobre el código con su evidencia, y qué falta hacer. Esa historia estaba repartida en cuatro
  pestañas y había que reconstruirla de memoria en cada PR.
- Seis estados por hilo, ordenados por lo que espera algo tuyo: te contestaron y falta responder,
  respuesta redactada para publicar, no se corrigió, sin verificar, sin publicar, resuelto. Lo
  accionable queda arriba.
- Una respuesta a **nuestra** respuesta reabre el hilo: la cadena se recorre entera por `parentId`,
  no sólo los hijos directos, así que la segunda vuelta de una discusión no se pierde.
- Las acciones están en la misma tarjeta: redactar, editar, publicar. No hay que saltar de pestaña
  para contestar lo que se está leyendo.
- **"Listo para mergear" es ahora un atributo visible del PR** en la lista, y gana sobre cualquier
  otro estado: es la conclusión, y es lo que uno busca de un vistazo. Sale de las mismas seis
  condiciones que habilitan el botón de mergear.
- Un hallazgo sin publicar cuenta como hilo abierto: esconderlo sería fingir que el PR está más
  cerrado de lo que está. Y un PR sin ningún hilo no se declara resuelto.

## 15.0.0

Requerimiento nuevo: **cuando llegan los fixes, se verifican solos**.

- Es la contracara del aviso "no hay commits desde la review". Cuando el autor por fin sube los
  arreglos, el barrido automático analiza los commits nuevos y decide, comentario por comentario,
  si fue atendido. Antes había que apretar "Verificar" en cada PR a mano.
- **Sólo corre donde hace falta**, y el motivo de cada salteo es explícito: el PR no está abierto,
  no hay review terminada, no queda nada por verificar, no hay commits desde la review, o ya se
  verificó contra ese commit exacto.
- Ese último caso es el que hace viable lo automático: se guarda **contra qué commit** se
  verificó, así el barrido no repite el análisis cada pocos minutos sobre un PR que no cambió.
  Cada verificación cuesta una corrida del modelo igual que una review.
- Un veredicto "a medias" o "sin corregir" sí se vuelve a mirar cuando llega un commit nuevo: son
  preguntas todavía abiertas. Lo descartado no se mira nunca, porque nunca se publicó.
- La verificación comparte el tope de reviews por ciclo, y va **antes** de revisar PRs nuevos:
  cerrar un PR que ya está casi listo vale más que empezar uno desde cero.
- Notifica al terminar, distinguiendo "todo corregido, se puede mergear" de "N siguen sin
  resolverse".
- En la pantalla del PR, si llegaron commits después de la última verificación, se avisa que quedó
  vieja: una verificación vieja es peor que ninguna, porque dice "corregido" sobre código que ya
  cambió.

## 14.0.0

Requerimiento nuevo: **aprobar y declinar pull requests**.

- Botones "Aprobar" y "Declinar" en la pantalla del PR, junto a verificar y mergear.
- **Aprobar va sin confirmación y sin condiciones**: es una opinión, se puede retirar, y no
  depende de la verificación —puede que quieras aprobar y que mergee otro.
- **Declinar pide el motivo en el mismo paso, y es obligatorio.** Se publica como comentario
  *antes* de cerrar: un rechazo sin explicación obliga a quien lo recibe a adivinar, y una vez
  cerrado el hilo queda menos a la vista. Si el cierre falla, el comentario queda igual — es
  preferible a perder la explicación.
- El diálogo dice lo que va a pasar: se cierra en el servidor y se puede reabrir, pero el autor lo
  va a ver rechazado.
- Ninguna de las tres se reintenta ante un error de red, sólo ante el 401 previo a la
  autorización: repetir una acción que el servidor ya procesó no es inofensivo.
- En GitHub "retirar la aprobación" no existe como tal —una review enviada no se borra— así que se
  publica una review de tipo COMMENT, que es lo más cercano sin pedir cambios que nadie pidió.

## 13.0.0

Requerimiento nuevo: **la urgencia crece con los días abiertos**.

- Cinco escalones en vez de un umbral único: reciente, no perderlo de vista (3 días), más de una
  semana (7), frenado (14) y abandonado (90). Cada uno con su color y su marca —`•`, `▲`, `▲▲`,
  `▲▲▲`— para distinguirlos de reojo sin leer el número.
- El motivo es concreto: en esta instalación conviven PRs de 2 días con dos de **1290 y 1269**
  días. Un solo umbral los mostraba igual.
- La urgencia no cambia ninguna regla: no bloquea, no reordena, no notifica. Sólo se ve. El orden
  de la lista sigue siendo el que elegís vos.
- Hay un test que recorre 400 días y exige que el nivel nunca baje: que un PR más viejo apure
  menos sería absurdo, y es el error fácil al insertar un escalón nuevo en el medio.

## 12.0.0

Requerimiento nuevo: **hace cuántos días está abierto el PR**.

- Cada fila de la lista y la cabecera del PR muestran "abierto hace N días", contado **desde el
  día en que se creó el PR** —no desde el último commit—: lo que se quiere saber es cuánto lleva
  esperando, y un commit nuevo no reinicia esa espera.
- A partir de una semana el número se muestra en rojo. No cambia ninguna regla; sólo hace saltar
  a la vista lo que se está durmiendo.
- Un PR sin fecha de creación conocida —cacheado antes de que se guardara— no muestra nada, en vez
  de inventar que se abrió hoy.
- Una fecha futura, por relojes desfasados entre el servidor y esta máquina, se muestra como
  "abierto hoy" y no como un número negativo.

## 11.0.0

Requerimiento nuevo: **verificar si los comentarios fueron atendidos, y recién ahí mergear**.

- Botón "Verificar si se corrigió": corre Claude Code sobre lo que cambió **desde la review**
  —`<commit revisado>..<head actual>`, que es exactamente la pregunta— y da un veredicto por cada
  comentario publicado: corregido, a medias o sin corregir.
- Cada veredicto viene con su evidencia concreta —archivo y línea, o el commit—, que es lo que
  permite discutirlo en vez de creerlo. Más un resumen global.
- El prompt es explícito en que juzgue por el código y no por lo que alguien haya dicho: que el
  autor conteste "ya está arreglado" no es evidencia. Ese es justo el error que haría mergear algo
  sin corregir.
- Un veredicto que no abrió el diff se rechaza: sin haber mirado el código no vale nada, y creerle
  habilitaría un merge.
- Si el modelo deja comentarios sin veredicto, se dice y esos siguen contando como sin verificar.
- **Mergear ahora exige la verificación.** Dos condiciones nuevas: no puede haber comentarios sin
  verificar, ni ninguno que haya dado "a medias" o "sin corregir". Un comentario descartado no
  necesita verificación, porque nunca se publicó.
- La lista de PRs aplica exactamente las mismas condiciones, con una consulta de conteos en vez de
  cargar los hallazgos por fila.

## 10.0.0

Requerimiento nuevo: **mergear desde la lista de pull requests**.

- El botón de mergear aparece también en cada fila de la lista, sin tener que entrar al PR. Sólo
  aparece cuando de verdad se puede: un botón apagado en cada fila sería ruido, y el motivo por el
  que no se puede ya se explica en la pantalla del PR.
- La condición es **la misma función** que usa la pantalla del PR, evaluada sobre los conteos que
  la lista ya tiene en memoria: no se carga un hallazgo por fila. Hay un test que compara las dos
  formas de invocarla caso por caso, porque si divergen, la que se relaje de más habilita un merge
  que no se puede deshacer.
- Después de mergear, la lista se refresca forzando el caché: el PR mergeado tiene que desaparecer
  de los abiertos, y el caché vive 60 segundos.
- La confirmación es la misma: modal, con las ramas nombradas y la opción de borrar la de origen.

## 9.0.0

Requerimientos nuevos: **descartar hallazgos** y **mergear el PR desde la app**.

- Un hallazgo se puede **descartar**: deja de contar como pendiente sin borrarse, y se puede
  deshacer. Antes sólo existía publicado o pendiente, así que un nitpick que uno decide no mandar
  dejaba el PR contando como "listo para publicar" para siempre. En la base real quedaron tres PRs
  así, cada uno con un `minor` sin publicar.
- El panel deja de contar una review cuyos hallazgos están todos resueltos —publicados o
  descartados—, aunque nunca se haya mandado el comentario resumen.
- **Mergear el pull request**, con condiciones explícitas: no puede quedar ningún hallazgo sin
  resolver, ninguna nota propia sin publicar, ninguna respuesta sin contestar, y tiene que haber
  commits nuevos desde la review —si el head es el mismo que se revisó, nadie corrigió nada y
  mergear sería aprobar sin verificar—. Cuando no se puede, el botón dice cuál de esas condiciones
  falta, en vez de estar apagado sin explicación.
- El merge pide confirmación en un modal que nombra las ramas y ofrece borrar la de origen. Es la
  única acción de la app que cambia el repositorio y no tiene vuelta atrás; no se reintenta sola,
  porque repetir un merge que el servidor ya procesó no es inofensivo.

Arreglo:

- **Un fallo al publicar ya no se pierde.** El error vivía en un snackbar que se iba solo, así que
  el hallazgo quedaba pendiente sin que nadie supiera por qué. Ahora se guarda en el hallazgo, se
  muestra y se puede reintentar o descartar.

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
