# Changelog

Reglas de numeración en [CLAUDE.md](CLAUDE.md): un requerimiento **nuevo** incrementa *major*;
cambiar uno **existente** incrementa *patch*.

## 83.0.0

**Un PR mergeado sale de todas las secciones del tablero.** Hasta ahora la app no tenía forma de
enterarse de que un PR se cerró: el caché guarda sólo los abiertos y se reemplaza entero, así que un
PR mergeado simplemente desaparecía de ahí — pero las respuestas sin contestar y los hallazgos sin
verificar viven en otras tablas, indexados por número de PR, y nadie les avisaba. "Te respondieron"
mandaba a un PR que ya nadie puede tocar.

La señal no cuesta una llamada extra: **lo que estaba en la lista de abiertos y ya no está, se
cerró**. Eso se anota al refrescar, y todas las secciones lo filtran desde un solo lugar — filtrar en
cada consulta habría significado acordarse en seis lugares distintos, y el que se olvide es el que
va a mostrar trabajo sobre un PR cerrado.

Dos cuidados que valen la pena:

- **Con la lista vacía no se concluye nada.** Un repositorio puede quedarse sin PRs abiertos, sí,
  pero una respuesta vacía también puede venir de un error que no falló del todo — y dar todo por
  cerrado de golpe borraría el tablero entero.
- **Un PR reabierto vuelve a pedir trabajo.** Y se anota el cierre en vez de borrar lo pendiente:
  borrar historial para limpiar una lista es cambiar el pasado.

En **actividad reciente** los PRs cerrados sí siguen apareciendo, ahora marcados. Ahí lo que importa
es qué pasó, no qué falta hacer, y esconderlos sería borrar el pasado; pero sin la marca uno hace
click esperando trabajo y se encuentra con un PR mergeado.

## 82.0.1

**Se revierte el filtro de PRs aprobados que introduje en la versión anterior.** Estaba mal por dos
motivos.

El que se ve: las aprobaciones se leen de la base **después** del primer dibujo, así que un PR
aprobado aparecía y un instante después desaparecía solo. Un filtro que depende de un dato que llega
tarde siempre parpadea.

El de fondo: **aprobado no es mergeado**. El PR sigue abierto y puede seguir necesitando trabajo, y
lo que se quiere esconder son los cerrados — para eso ya están los filtros de estado, que además son
explícitos. Agregué un filtro nuevo para un problema que ya tenía solución, y encima cambié el
comportamiento por defecto.

También: tres tests dependían de que la rama `feature/pos-ar-fiscal` siguiera existiendo en el clon.
Se borró al mergear el PR —lo mismo que hace un rato investigamos en el 155— y desde entonces
fallaban por el calendario y no por el código. Ahora se saltean cuando la rama no está.

## 82.0.0

**Una respuesta a un comentario ahora dispara la verificación.** Es el bug que reportaste del PR 128:
los cuatro hallazgos estaban contestados uno por uno en Bitbucket y la app los mostraba "sin
verificar" — para siempre. La regla sólo se disparaba con **commits nuevos**, y una respuesta no es
un commit.

Esa cautela tenía sentido: sin código nuevo, re-juzgar el mismo código da lo mismo y cuesta una
corrida. Pero dejaba afuera la señal más fuerte que hay — una persona explicando qué pasa con el
hallazgo. Un commit hay que interpretarlo; la respuesta lo dice.

Y las respuestas ahora van **pegadas al hallazgo que contestan**, no todas juntas al final: antes el
modelo ni siquiera sabía cuál contestaba a cuál. La regla de juicio también cambió — "ya lo arreglé"
se sigue comprobando en el código, pero "no aplica porque X" o "se hace en otro PR" ahora se pueden
cerrar como **no se va a hacer**, un estado nuevo. Sin él, esos hallazgos quedaban pendientes para
siempre, y una lista que nunca se vacía deja de mirarse.

**Un PR cuya rama ya no existe explica por qué no muestra archivos.** El PR 155 de `kubrik-erp-be`
tiene un archivo en Bitbucket y acá no se veía nada: la rama se borró al mergear, el clon no la puede
traer, y el panel quedaba en blanco. Un panel vacío se lee como "todavía cargando" y uno espera algo
que no va a llegar. Ahora dice cuál de las tres cosas pasó: la rama ya no está, el fetch falló, o el
PR de verdad no cambia nada. Además se traen las ramas antes de listar — sin eso, un clon que nunca
vio esa rama mostraba cero archivos sin decir nada.

**Los PRs aprobados no aparecen por defecto.** Un PR aprobado ya no espera nada: el barrido no lo
toca y no hay nada que decidir. Mezclado con los demás sólo hace más larga la lista donde uno busca
lo que sí pide algo.

## 81.1.0

**La lista de implementaciones dice dónde escribe cada una**: en su taller o directo en tus clones.
Es la diferencia entre "esto puede estar tocando mi árbol de trabajo ahora mismo" y "no", y no estaba
a la vista en ningún lado.

Cuando el taller ya no está en disco lo dice también —"devuelto y limpio"— porque eso es distinto de
nunca haber tenido uno, y sin distinguirlos una implementación terminada se vería igual que una que
trabaja directo en tus clones.

## 81.0.0

Más sobre la gestión de talleres, con el foco en el peor caso silencioso.

**El trabajo vuelve a tus clones en todos los finales, no sólo cuando termina bien.** Antes, una
implementación que quedaba esperando una decisión, que fallaba, o que alguien frenaba, dejaba todo lo
que había hecho únicamente adentro del taller. Desde afuera parecía que no había hecho nada, y si
nadie la retomaba, no lo hizo para nadie. Ahora se devuelve siempre; **borrar** sigue siendo otra
cosa y pasa sólo cuando terminó de verdad, porque una implementación que se va a retomar necesita su
taller con todo lo commiteado.

**Un barrendero al abrir la app** borra los talleres de implementaciones terminadas cuyo trabajo ya
está del otro lado. Devolver puede fallar por algo pasajero —el clon estaba parado en esa rama— y
entonces el taller queda, correctamente, sin borrar; si más tarde alguien devuelve el trabajo a mano,
nadie volvía a limpiar y la carpeta ocupaba disco para siempre. Sólo toca lo que puede verificar.

**"¿Ya está a salvo?" se contesta por commit y no por nombre de rama.** Un taller recién creado
aparecía como "con trabajo sin devolver" siempre: su rama apunta al mismo commit que la base, que el
clon obviamente tiene, pero bajo otro nombre. Un aviso que casi siempre está equivocado es peor que
no avisar — enseña a ignorarlo, y el día que dice la verdad nadie lo mira.

**El taller se ve desde la implementación**, con su estado y el botón para devolver el trabajo sin
esperar a que termine — que es lo que permite mirarlo con las herramientas de siempre mientras sigue
corriendo. El detalle y el borrado se quedan en administración: borrar no es algo que uno haga
mirando el avance.

Y en administración, el total en disco y cuántos talleres tienen trabajo sin devolver, con esos
primeros en la lista: uno con trabajo pendiente entre diez limpios se pierde de vista, y es el único
que importa.

## 80.1.0

Barrido sobre los talleres, que son lo más nuevo y lo más riesgoso. **La pantalla mentía durante toda
una corrida con taller**: los commits, los diffs y el contador seguían leyendo tu clon mientras el
trabajo pasaba en la copia. O sea que una implementación podía llevar diez commits hechos y el
tablero mostrar cero — la misma clase de error que arreglamos en la v73, otra vez, por el mismo
motivo: dos fuentes para una sola verdad.

Ahora hay **una sola función** que contesta dónde vive un repositorio para una implementación, y la
usan el motor y las pantallas. Mira el disco y no la marca: cuando la implementación termina y el
taller se borra, todo pasa a estar en el clon, y preguntando por la marca la pantalla seguiría
buscando en una carpeta que ya no existe.

**Planificar, auditar y analizar miraban el código sin lo que las tareas habían escrito.** Una
replanificación a mitad de camino no veía nada de lo hecho y podía volver a proponer trabajo que ya
estaba. Ahora los tres miran el taller cuando lo hay.

**El árbol sucio de tu clon ya no bloquea nada** con taller: no se toca. Ese bloqueo era la razón por
la que "retomar" parecía muerto, y con taller no tiene sentido que siga.

**El tamaño que mostraba era casi veinte veces el real.** `du` informa 708 MB para un clon cuyo costo
verdadero es 36 MB, porque `--local` enlaza los objetos en vez de copiarlos. Con ese número a la
vista, alguien borra un taller para recuperar espacio que nunca gastó y se lleva puesto el trabajo.
Ahora se suman sólo los archivos que desaparecerían de verdad.

Medido sobre un repositorio real de 1,7 GB: **cuatro segundos** para clonar y 36 MB de disco propio.
Lo que el taller no se lleva es lo que no está versionado —`target/`, `node_modules`, cachés—, y eso
tiene un costo que conviene saber: la primera compilación adentro arranca en frío.

También: un interruptor para elegir dónde trabaja cada implementación —bloqueado una vez que hay
commits, porque cambiarlo dejaría la mitad del trabajo de cada lado—, y las carpetas huérfanas ahora
se pueden borrar, a ciegas y diciéndolo: sin implementación no hay rama ni clon con el que comparar,
así que la app no puede afirmar que no se pierde nada. Dejarlas sin salida era peor.

## 80.0.0

**Cada implementación trabaja en su propio taller.** Hasta ahora escribía directamente en tu clon, y
eso traía tres problemas de distinta gravedad: tu árbol de trabajo se llenaba de cambios a mitad de
camino; dos implementaciones sobre el mismo repositorio se peleaban por esa única copia; y cualquier
cosa que tuvieras sin commitear bloqueaba el arranque o quedaba mezclada con lo generado.

Ahora se clona cada repositorio afectado a un taller propio y todo el trabajo pasa ahí. El clon es
`--local`: enlaza los objetos en vez de copiarlos, así que un repositorio de dos gigas se clona en un
segundo. Sin eso esto sería inviable.

**Uno por implementación y no uno por tarea.** Dos tareas del mismo repositorio siguen sin poder
correr a la vez —comparten el árbol— pero dos implementaciones distintas ya no se estorban, que es
donde estaba el choque real. Y como no se toca tu clon, lo que tengas sin commitear deja de importar:
el bloqueo que hasta ahora impedía arrancar desaparece de raíz.

**El trabajo no vive en el taller.** Al terminar se empuja cada rama a tu clon local —no al remoto,
que es una decisión tuya— y recién entonces se borra, después de comparar los dos shas. Ese orden es
todo: borrar primero y verificar después es como se pierde el trabajo de una tarde, y con un taller
por implementación el trabajo de una tarde es exactamente lo que hay adentro. Si algo no se pudo
devolver, el taller se conserva y se dice por qué.

Sólo se limpia cuando la implementación terminó de verdad. Una que quedó esperando una decisión o con
tareas fallidas se va a retomar, y para eso necesita su taller con todo lo que hay commiteado.

**Una sección de administración** muestra cada taller con sus repositorios, el sha de los dos lados y
lo que ocupa. El botón de borrar sólo aparece cuando los shas coinciden: para una acción que no tiene
vuelta, confirmar no alcanza — tiene que estar verificado. Las carpetas que quedaron de
implementaciones borradas se listan aparte y no se tocan: no hay rama ni clon con el que compararlas.

Las implementaciones que ya venían corriendo siguen trabajando en tu clon, como antes. Cambiarles el
modelo de ejecución a mitad de camino dejaría la mitad del trabajo en un lado y la mitad en el otro.

## 79.0.0

**Se registra lo que consume cada corrida del CLI**, y desde un solo lugar. Hay nueve sitios que
lanzan Claude y van a haber más; pedirle a cada uno que se acuerde de registrar es exactamente como
se termina con un registro parcial — que es peor que no tener ninguno, porque parece autoritativo y
las decisiones que se toman mirándolo salen mal. Cada corrida queda anotada con su tipo, su modelo,
su sesión, sus tokens y su costo.

Con eso hay dos ventanas: **las últimas cinco horas** —la que usa Claude Code— y **los últimos siete
días**. La primera contesta "¿lanzo algo grande ahora o espero?"; la segunda, cuánto va de la semana.
Y el reparto por actividad y por modelo, que son dos preguntas distintas: una dice qué consume, la
otra si conviene bajar de familia en alguna.

**Sobre el límite: la app no puede leerlo.** El CLI no lo expone, y el archivo de estadísticas que
Claude deja en disco no trae límites y se actualiza cuando quiere. Inventar un porcentaje contra un
tope adivinado sería lo peor de los dos mundos — un número que parece exacto y decide por vos. Así
que el tope semanal lo declarás vos, y la app aporta la cuenta fiel de su propio consumo, que es la
parte que causa y la única que puede medir. Avisa al 80%, no al 100%: avisar cuando ya no se puede
hacer nada con el aviso no es avisar.

**Un `529 Overloaded` ya no mata una implementación.** Un error así no dice nada sobre el trabajo:
dice que en ese instante había demasiada gente. Ahora se distingue lo pasajero —529, 429, timeouts,
conexión cortada— de lo que no se arregla esperando, como un modelo inexistente: tratar eso como
pasajero convertiría un error de configuración de un segundo en una espera infinita.

Lo pasajero se reintenta **cada cinco segundos durante el primer minuto**, y sólo después se estira a
quince y treinta. Pasado el minuto el problema ya no es un bache, y machacar no lo apura: suma carga
al servidor que está justamente sobrecargado. No hay tope de intentos — la salida es cancelar, que es
una decisión de una persona; un tope fijo haría que la tarea se dé por vencida justo cuando el
servidor estaba volviendo.

**Un pedido de la consola ahora se lee antes de convertirse en tarea.** Antes entraba crudo: el texto
como descripción, sin pasos, sin tamaño, sin saber en qué repositorio va ni a qué se refiere — y todo
eso lo tenía que adivinar el modelo que escribe el código, que es el peor momento. Ahora una lectura
lo convierte en una tarea con título, pasos, repositorio elegido mirando dónde vive lo que se
menciona, y **la tarea que está corrigiendo**.

La importancia la decide esa lectura y no quien escribió: el que pide una corrección siempre la
siente urgente, y si todo es urgente la prioridad deja de ordenar nada. Se compara contra lo que
falta hacer, en cuatro niveles — crítica, alta, normal, baja — y de ahí sale el lugar en la cola.

El pedido original queda **textual** en la tarea, antes de la interpretación: es lo que permite ver
si le entendió. Y si la lectura no se puede hacer, el pedido entra igual, crudo — perder una
instrucción porque no se pudo interpretar sería el peor de los dos mundos, porque quien la escribió
ya se fue.

## 78.0.0

**Los commits pasaron a ser una sección propia.** Estaban en un desplegable dentro del detalle, y
ahí el diff no entraba: cuatrocientos píxeles de alto compartidos con el resto de la implementación,
para leer código que necesita ancho y contexto. Uno terminaba abriendo el repositorio en otra
herramienta, que es exactamente lo que la app venía a evitar.

Ahora es una pantalla con la lista a la izquierda y el commit elegido a la derecha, con todo el
ancho. Al costado y no expandiendo la lista: con quince commits, expandir el octavo empuja los siete
anteriores fuera de la vista y se pierde el lugar donde uno estaba.

En el detalle queda una línea con el número de commits que lleva ahí, y desde la pantalla hay un
**volver a las tareas** explícito además de las migas — mirar un commit para entender una tarea es
el camino más común, y no tiene que depender de reconocer que el título del medio es un enlace.

## 77.0.0

**Se pueden ocultar repositorios sin perderlos.** Con quince, los tres que uno mira todos los días
quedan enterrados entre los doce que se agregaron para una implementación puntual.

Ocultar es una decisión de pantalla y nada más: el repositorio sigue existiendo, sus reviews y sus
hallazgos siguen ahí, y una implementación que lo use sigue corriendo igual. Esa es toda la
diferencia con borrar —que ya existía— y es la que permite ocultar sin pensarlo dos veces. Los
ocultos quedan detrás de un desplegable al pie, en una línea cada uno: lo que se busca ahí es el
nombre para traerlo de vuelta, no su estado.

## 76.0.0

**Un servicio que los documentos nombran ahora se averigua antes de decidir nada.** La versión
anterior prohibía todo lo que no estuviera en la lista de repositorios, y eso era demasiado grueso:
`oauth2`, `mail-ms`, `scheduled-tasks-ms` pueden ser dos cosas muy distintas que se parecen y se
resuelven al revés.

- **Un módulo de un repositorio que ya está** —un módulo Maven, un subdirectorio, algo que el
  `settings.gradle` raíz incluye—. Entonces no falta nada: la tarea va en ese repositorio y el
  detalle dice en qué carpeta. Prohibirlo habría hecho fallar tareas que estaban bien.
- **Un clon aparte que nadie declaró.** Ahí sí no se planifica, porque lo que se escriba queda
  suelto encima de la rama que ese clon tuviera abierta.

Para el segundo caso el plan devuelve **qué falta declarar, con la evidencia de cómo lo dedujo**:
"existe en `../mail-ms` con su propio `.git`", "no aparece en el `settings.gradle` ni como carpeta".
Sin eso, "falta mail-ms" es una afirmación que hay que ir a verificar a mano; con eso se decide en
un vistazo si agregarlo o si el modelo buscó mal.

Eso aparece arriba de todo en la implementación, con un botón que **lo declara de un click** usando
la ruta que encontró. Casi siempre la respuesta no es "el plan está mal" sino "falta declarar un
repositorio", y eso se arregla en dos minutos si alguien se entera.

## 75.0.0

**Una consola para darle trabajo a la implementación mientras corre.** El módulo es autónomo y ese
sigue siendo el punto, pero autónomo no quiere decir sordo: mirando el resultado uno ve cosas que el
plan no podía ver —una tarea que resolvió algo de una forma que no sirve, un detalle que faltaba en
las specs— y hasta ahora la única salida era frenar todo, editar, replanificar y volver a arrancar.
Para una corrección de dos líneas, eso es tirar media hora de trabajo en curso.

Lo que se escribe entra como una tarea más, con dos diferencias:

- **Puede pasar al frente.** Una corrección existe para atenderse antes que lo que quedaba; si
  tuviera que esperar su turno al final de la fila, llegaría cuando ya no sirve. La prioridad va en
  una columna aparte del número de tarea: el número es una referencia estable —"la 4 depende de la
  1", el mensaje de un commit— y renumerar para meter algo en el medio rompería todas esas
  referencias de golpe. La tarea se agrega al final y corre primero.
- **Queda marcada como escrita a mano**, y quien la ejecuta lo sabe: manda sobre el plan, y como
  casi siempre es una corrección de algo que ya está escrito, lo primero que hace es ir a mirarlo en
  vez de ponerse a escribir.

No mata lo que está corriendo. Una tarea a mitad de camino tiene un proceso escribiendo archivos, y
cortarlo para adelantar otra deja el árbol a medias: la urgente entra en cuanto se libere un lugar.
Para lo que no puede esperar eso, está el botón de frenar.

Arriba del campo queda el historial de lo pedido, con en qué terminó cada cosa. Sin eso, escribir
una instrucción y verla desaparecer entre veinticinco tareas se siente como hablarle a un pozo.

## 74.0.0

**Los commits de la rama se abren y muestran su código.** La lista decía qué se hizo y cuándo, y no
había forma de ver qué: contestar eso obligaba a abrir el repositorio en una terminal, o sea salirse
de la herramienta justo en la pregunta más común. Ahora cada commit se despliega en sus archivos, y
cada archivo en su diff — lo mismo que ya mostraba una tarea, partiendo de un sha suelto. Los
archivos se leen del historial de git y no se guardan: git no se va a ningún lado, y duplicarlos en
la base sería mantener dos versiones de la misma verdad. Al lado, un botón que abre la carpeta del
repositorio, para lo que la app no hace.

**Se puede poner un tope de tareas simultáneas.** El motor ya lanzaba en paralelo lo que las
dependencias permiten, con un límite físico: nunca dos en el mismo repositorio, porque dos modelos
escribiendo el mismo árbol se pisan. Pero ese no es el único límite que importa — en una
implementación de seis repositorios son seis procesos de Claude a la vez, que cuestan seis veces y
ocupan una máquina que alguien está usando. El tope se aplica **después** del filtro por
repositorio: cortando antes, una tarea del segundo repositorio quedaba afuera por culpa de una del
primero que se descartaba igual. Sin tope sigue mandando el criterio del motor.

**Las duraciones que quedaban en minutos crudos pasaron a `HH:MM`**, incluido el eje del Gantt: un
plan de ocho horas con marcas cada "240m" obliga a dividir para ubicarse.

## 73.0.0

**El trabajo se escapaba del repositorio y nadie se enteraba.** Esto explica el "sigue escribiendo
en develop", y era peor de lo que parecía: la rama **sí** se creaba y **sí** se usaba. Lo que pasaba
es que siete tareas escribieron en `../timelog-ms` —un repositorio vecino que los documentos
mencionaban y que no era parte de la implementación—. Ahí no hay rama creada ni nadie commitea, así
que los archivos quedaron sueltos encima de `develop`, invisibles para la herramienta. Las tareas
dieron DONE, el repositorio asignado quedó limpio, no hubo commit, y el tablero decía que todo había
salido bien.

Tres cambios, uno por cada lugar donde esto pasó desapercibido:

- **El planificador ya no puede inventar repositorios.** Se le dice que los de la lista son todos
  los que hay, incluso si los documentos nombran carpetas hermanas. Si algo necesita uno que no
  está, va en el resumen y queda fuera del plan: que falte una parte y esté dicho se arregla en dos
  minutos agregando el repositorio; que se escriba a escondidas no se descubre hasta que alguien
  encuentra los archivos sueltos.
- **La tarea tiene prohibido escribir fuera de su árbol.** Si sólo se puede hacer tocando otro
  repositorio, no se hace: se reporta y falla.
- **Terminar sin cambios dejó de ser un éxito.** Un árbol limpio después de una tarea de código
  significa una de dos cosas, y las dos son un problema: o no hizo nada, o lo hizo en otro lado.
  Ahora la tarea falla diciendo cuál de las dos, y si escribió afuera, nombra los archivos.

**Verificamos quedar en la rama.** `git checkout` falla en silencio —una base que no existe, un
archivo que se pisaría— y el resultado se descartaba. Ahora, si el repositorio no quedó en la rama
de la implementación, no se ejecuta nada.

**Se puede frenar una tarea desde su detalle.** Y el símbolo de "corriendo" dejó de ser un ▶, que se
lee como un botón de play —"esto está detenido, arrancalo"— justo cuando es lo contrario.

**Analizar los documentos ahora es un job**, con su registro guardado en la base y un final
explícito. Sin eso, un análisis cortado y uno que nunca se lanzó se veían igual desde afuera.

**En la lista de trabajos está el id del job y el de su tarea**, completos y seleccionables, para
poder rastrear qué disparó qué. Y en el Gantt, el nombre de cada tarea toma el color de su estado:
en una lista de veinte, la barra queda lejos del texto.

## 72.1.0

**Las acotaciones de la revisión son complementarias.** El campo pasó a llamarse "Acotaciones
(opcional)" y dice lo que hace: se suman al criterio de revisión, no lo reemplazan.

Sin nada escrito, el bloque de acotaciones directamente no aparece en el prompt. Antes se rellenaba
con una frase inventada —"no hay nada puntual para corregir, buscá vos qué está flojo"— que es
ponerle palabras a alguien que no habló, y el modelo las leía como una instrucción más. El criterio
de revisión ya está completo por sí solo, y ahora lo dice explícitamente: es lo que hay que hacer
haya o no acotaciones.

Con texto, se presenta como lo que es —"esto pidió quien lo mandó a revisar", después del criterio y
no en su lugar— y con una regla clara para el desempate: si una acotación contradice el criterio
general, mandan las acotaciones. Quien las escribió conoce el proyecto; el criterio general no. Sin
decirlo, el modelo tenía que adivinar a cuál hacerle caso.

## 72.0.0

**Replanificar ya no destruye lo que pasó.** Antes reemplazaba el plan entero: se perdía el registro
de las tareas hechas —la única forma de saber qué produjo cada commit— y, si algo estaba corriendo,
su fila desaparecía debajo del proceso que seguía escribiendo archivos.

Ahora **lo hecho y lo que está corriendo se quedan**, y sólo se reemplaza lo que no empezó. Al
planificador se le dice cuáles son intocables para que planifique nada más que lo que falta, y las
tareas nuevas se numeran a continuación de las que quedan — con sus dependencias traducidas, porque
una tarea nueva que dependía de "la 2" habría terminado esperando a la 2 vieja, que es otra cosa y
en el peor caso ya está terminada.

Como consecuencia, el aviso al confirmar dejó de hablar de pérdidas: dice cuántas se reemplazan y
cuántas quedan.

**Replanificar ya no obliga a escribir nada.** El prompt de revisión ya dice qué mirar —orden,
tamaño, pasos—, así que exigir un texto era pedir que alguien redacte lo que el sistema ya sabe
pedir. Con el campo vacío, revisa con su propio criterio.

**Cuántas veces se replanificó, y cuándo fue la última.** Contestan cosas distintas: el contador
dice si el plan es inestable —tres replanificaciones seguidas son una señal de que el problema no
está en el plan—, y la fecha dice si lo que estás mirando es de antes o de después del último
cambio.

**Los tres botones de análisis explican qué hacen y qué dejan.** "Analizar los documentos", "Auditar
contra las specs" y "Revisar y replanificar" son fáciles de confundir por el nombre y muy distintos
al ejecutarse: uno lee las specs, otro compara el plan contra ellas, el tercero rehace el plan. El
que duda termina apretando el que suena más inofensivo, que no siempre es el que necesita.

## 71.3.0

**Se acabó el recargar todo.** El detalle de una implementación tenía un solo latido leído arriba de
todo, así que cada tic recomponía la pantalla entera —encabezado, repositorios, botones, plan,
tabla— para mostrar que una barra se movió un punto. Y el feed de actividad, que cambia con cada
línea que emite el motor —decenas por segundo—, se leía en el mismo lugar: esa frecuencia se
contagiaba a todo.

Ahora cada parte se refresca sola, al ritmo que necesita:

- **Las tareas** —avance, revisiones, esfuerzo, diagrama, tabla y commits— viven en una sección que
  late cada tres segundos y lee sólo lo suyo.
- **El feed** es su propio componente: es lo único que tiene que redibujarse a la velocidad del log.
- **El padre** late cada cinco segundos y sólo para que el estado y los botones se enteren de que la
  implementación arrancó o terminó. Cuando una sección se actualiza, el resto ni se entera: sus
  datos no cambiaron, así que Compose lo saltea.

Hay tests que fijan la estructura —quién lee el estado que cambia rápido— porque es una decisión que
se pierde sola en la próxima edición y el síntoma no aparece hasta que algo corre.

## 71.2.0

**La tabla de tareas se vaciaba y se volvía a llenar todo el tiempo.** Eran dos cosas sumadas.

La primera: al recargar, el valor volvía al inicial —lista vacía— hasta que la consulta contestaba.
Con una pantalla que se refresca sola, eso es la tabla parpadeando y el scroll saltando varias veces
por segundo. Ahora lo que ya está en pantalla se queda hasta que llega lo nuevo. Sólo se vuelve al
valor inicial cuando cambia la **identidad** de lo que se mira —otra implementación, otra tarea—,
porque ahí lo viejo pertenece a otra cosa y mostrarlo sería mentir.

La segunda: las consultas estaban atadas al feed de actividad, que cambia con **cada línea de log**
—decenas por segundo mientras una tarea trabaja—. El latido de tres segundos ya trae lo que haya
cambiado en la base; el feed sólo tiene que dibujar el feed.

## 71.1.0

**"Retomar" parecía un botón muerto, y no lo era.** El motor rechaza la corrida cuando un
repositorio tiene cambios sin commitear que no son de esta implementación, y lo hacía en el primer
instante: apretar dejaba en pantalla el mismo error que ya estaba, sin que nada cambiara. Desde
afuera eso es exactamente igual a un botón que no hace nada.

Ahora el bloqueo se ve **antes** de apretar: el botón queda deshabilitado, al lado dice qué
repositorio lo traba y hay un "guardarlos y desbloquear" que resuelve los tres de una. Un botón que
no puede funcionar no tiene que parecer que puede. Y el rechazo también queda en el feed, así que
apretar deja rastro aunque falle al instante. El mensaje además dice en qué rama están esos cambios,
que es lo que permite reconocerlos como propios.

**La pantalla dejó de trabajar de más mientras algo corre.** Le preguntaba a git por cada
repositorio en cada recomposición —dos subprocesos por repositorio, en el hilo de la interfaz— y
releía todo una vez por segundo. Ahora el estado de git se mide cuando cambia algo, fuera del hilo
de la interfaz, y el latido pasó de un segundo a tres: una tarea dura minutos, así que refrescar
tres veces más seguido no adelanta ninguna noticia.

**Desde la lista de trabajos se puede abrir el contexto** de la tarea que ese job estaba corriendo.
Es lo que contesta la pregunta que trae a alguien ahí cuando ve un job muerto: qué alcanzó a hacer
antes de cortarse.

## 71.0.0

**Cincuenta y cuatro textos mostraban el marcador crudo.** `Vivos ({0})`, `3 de {1} tareas`,
`intento {0}`: todo lo que agregué desde la v63 usaba `{0}` para los parámetros, y la app formatea
con `String.format`, que sólo entiende `%s`. El compilador no dice nada —es una cadena válida— y el
test de traducciones tampoco, porque la clave existía en los dos idiomas. Ahora hay un test que
falla si un texto usa marcadores que no se sustituyen, y otro que formatea cada texto con argumentos
de prueba para que un marcador mal escrito explote acá y no en la pantalla de alguien.

**Las duraciones pasan a `HH:MM` cuando superan la hora.** "185 min" obliga a dividir por sesenta
para saber si son tres horas o cinco, y ese cálculo se hace mal justo cuando la cifra importa
—cuando algo se está yendo de tiempo—. Debajo de la hora siguen siendo minutos, que es como se
piensa una tarea.

**Una carpeta de documentos se lee entera.** Todas las subcarpetas, y no sólo los `.md`: también
`.txt`, `.rst`, `.adoc` y demás formatos de texto. Las specs reales aparecen exportadas de un
documento o sacadas de una wiki, y aceptar sólo Markdown hacía que una carpeta llena de
requerimientos se leyera como vacía — con un "0 documentos" que parecía un error de la app cuando
era una decisión suya. Se saltean `node_modules`, `build`, `.git` y compañía: una carpeta de specs
suele vivir dentro de un repositorio, y sin eso una sola elección arrastraba miles de archivos que
se comían el presupuesto antes de llegar a la spec que importaba.

**Una carpeta ya no pregunta de qué rama partir.** Elegir rama base tiene sentido con un repositorio
conectado, donde hay un develop, un main y ramas de otros. Cuando alguien señala una carpeta está
diciendo "el código va acá". Y si la carpeta se acaba de inicializar y no tiene ningún commit, no
hay de dónde partir: se queda donde está y el primer commit de la primera tarea abre la rama.

## 70.1.0

Barrido de bugs sobre lo que se agregó en las últimas versiones, buscados releyendo el código con la
pregunta "¿qué pasa si la app se muere justo acá?".

**Retomar después de un corte era imposible.** El árbol de trabajo queda sucio *porque* la tarea no
llegó a commitear, y la comprobación de "hay cambios sin commitear" se negaba a arrancar — o sea que
el mecanismo entero de contexto y reanudación nunca se podía usar. Ahora, si estamos parados en la
rama de esa implementación, esos cambios son nuestros y se sigue desde ahí; si no, se sigue
avisando, porque entonces sí son de otro.

**Una implementación cortada quedaba diciendo "corriendo" para siempre.** El estado del motor es en
memoria: los procesos murieron con la app y nadie las cerraba. Peor, el botón ofrecía frenarlas en
vez de retomarlas. Ahora quedan en "frenada" —nadie las intentó y perdió, se cortaron— y desde ahí
el botón dice lo correcto.

**Planificar no tenía job.** Una app que se cerraba mientras planificaba dejaba la implementación en
"planificando" sin nada que lo delatara: no hay tarea que mirar, porque el plan es justo lo que
todavía no existe.

**El job de la implementación no latía.** Una implementación de dos horas aparecía "sin latido" a
los dos minutos: la única señal que sirve para distinguir vivos de cadáveres estaba mintiendo justo
en el caso normal. Y cerraba siempre como "listo", así que una implementación fallida o esperando
una decisión dejaba un job diciendo que salió todo bien.

**Una tarea terminada podía volver a la cola.** Ventana angosta —la app muere entre que la tarea se
marca terminada y que su job se cierra— pero el efecto era rehacer trabajo que ya tenía commit.

**Dos revisiones simultáneas compartían la clave de su proceso**, así que cancelar mataba una sola y
la otra seguía escribiendo.

Además: `exec` era la única puerta que esquivaba el monitor de la conexión —con SQLite eso no da un
error claro sino resultados raros—, la pantalla de trabajos pedía una consulta por implementación
cada cinco segundos, y el contexto de una tarea en curso no se actualizaba solo.

## 70.0.1

**La carpeta elegida ahora se ve.** Se registraba bien, pero la lista de repositorios que dibuja el
formulario se había cargado al abrirlo, así que la carpeta recién agregada no existía para la
pantalla: no aparecía por ningún lado, que desde el otro lado es indistinguible de que no hubiera
pasado nada.

Ahora la lista se relee al agregar una carpeta, y además cualquier cosa seleccionada que no esté en
la lista se busca por id. Lo segundo es una red y no la vía principal: el picker sólo dibuja lo que
hay en la lista, y una selección sin fila que la represente no se ve en ningún lado.

## 70.0.0

**Se puede implementar sobre una carpeta, sin conectar ningún repositorio.** Hasta ahora había que
dar de alta el repositorio con su proveedor, su owner, su slug y su token — y todo eso existe para
poder revisar PRs. Para escribir código no hace falta ninguno de los cuatro: alcanza con saber en
qué carpeta. Pedirlo igual convertía "implementá esto acá" en un trámite de cinco campos, y dejaba
sin salida a quien todavía no conectó nada: el botón de nueva implementación estaba deshabilitado.

La carpeta se guarda como un repositorio más, porque *es* lo mismo: una base de código que la app
conoce. Duplicar el concepto habría obligado a que cada tarea, cada job y cada diff supieran de dos
clases de destino, a cambio de nada. Queda marcada como local, y con eso alcanza para que las
pantallas que hablan de PRs no la ofrezcan y para que nunca entre en revisión automática.

**Si la carpeta no es un repositorio de git, se le da uno.** El commit por tarea es la red de
seguridad del módulo entero: sin historial, que la séptima tarea falle se lleva puesto el trabajo de
las seis anteriores. Inicializar no toca nada de lo que ya había adentro.

Apuntar dos veces a la misma carpeta da el mismo destino. Sin eso, la segunda implementación
escribiría en "otro" repositorio que en realidad es el mismo, con dos historiales de tareas sobre
los mismos archivos.

## 69.0.0

**Un corte ya no es empezar de nuevo.** Hasta ahora, si la app se cerraba con una tarea a mitad de
camino, ese trabajo se perdía: el proceso moría con la app, la tarea quedaba marcada como corriendo
para siempre, y el próximo intento arrancaba desde cero sobre un árbol con archivos a medio
escribir.

Hay tres piezas, y cada una existe por una razón distinta.

**Los jobs.** Una tarea es una unidad del plan; un job es una unidad de ejecución. Hacen falta las
dos porque con el estado de la tarea sola no se puede contestar la única pregunta que importa
después de un corte: *lo que figura corriendo, ¿está vivo o es un cadáver?* La tarea quedó en
"corriendo" en los dos casos. **El job late** cada veinte segundos, y un job que dice correr sin
latido reciente está muerto — eso sí se puede afirmar sin adivinar. Al abrir la app, los cadáveres
se cierran como **interrumpidos**, que no es lo mismo que fallidos: fallar es que se intentó y salió
mal, interrumpirse es que nadie lo terminó. Mezclarlos haría buscar un error que no existe y, peor,
haría descartar trabajo que estaba bien encaminado.

**El contexto de cada tarea.** Hechos observados mientras la tarea corre —qué archivo tocó, qué
comando ejecutó, qué paso cerró—, una fila por hecho, sólo se agrega. Se guardan hechos y no un
resumen porque el resumen sólo llega al final, y en el único caso que importa no llega nunca. Y sólo
se agrega porque un blob mutable puede quedar escrito a medias cuando el proceso muere, sin forma de
distinguir uno truncado de uno real; una fila se escribe entera o no se escribe.

**La sesión del CLI.** Se anota apenas el CLI la anuncia, en el primer evento. Las sesiones de
Claude Code viven en disco, así que una que se cortó se puede **reanudar de verdad** con `--resume`:
el modelo recupera todo lo que ya había razonado, no un resumen de lo que hizo. Si la sesión no
está, el intento nuevo arranca con el contexto acumulado y con la lista de archivos que quedaron sin
commitear, avisándole que **son su propio trabajo a medio hacer**, no cambios ajenos que haya que
respetar o revertir.

**Una sección nueva, Trabajos**, para ver qué hay corriendo en toda la app: primero lo vivo, después
lo que se quedó sin latir —que es lo único que pide una decisión—, después lo cerrado. Con la edad
del latido en vez de la hora, porque lo que se decide mirando esto es si hay que hacer algo ahora.

Y en el detalle de una tarea, el contexto acumulado se ve entero. Es distinto de "qué hizo": eso lo
cuenta el modelo al final, y esto se escribe mientras pasa.

El azul de "corriendo" pasó a celeste, para separarse del verde de un vistazo.

## 68.0.0

**El Gantt es ahora un gráfico de verdad, y vive arriba de la tabla de tareas.** Sale de las mismas
tareas que la tabla —es el plan visto en el tiempo en vez de en orden— así que no hay nada nuevo que
cargar. Lo que se ve siempre es una tira: cada tarea, un tramo del ancho de su duración, coloreado
por su estado. Una barra de progreso sola dice "8 de 24"; esta dice además dónde están las que
fallaron y las que esperan una decisión, que es lo que decide si vale la pena abrir el diagrama.

Al tocarla se despliega el diagrama completo, dibujado y no armado con cajas:

- **Flechas de dependencia** en codo, con punta: dicen quién espera a quién sin tener que cruzar
  números. Una diagonal entre filas lejanas cruza media pantalla y se confunde con las barras que
  atraviesa.
- **Relleno parcial** en cada barra: el contorno es lo planificado, el relleno es lo hecho al
  momento de mirar. Con una sola forma habría que elegir entre mostrar el plan o mostrar el avance.
  La que está corriendo nunca se llena del todo — una barra llena mientras la tarea sigue trabajando
  diría que terminó.
- **La línea de ahora**, punteada: marca el tiempo real transcurrido sobre un eje dibujado en tiempo
  planificado. Que quede a la derecha de lo último terminado es exactamente la señal de que se está
  yendo de tiempo, y eso no se ve en ninguna otra pantalla.
- Eje con marcas redondas, duración escrita al lado de cada barra, y leyenda.

**Cada tarea dice si es paralelizable**, con un icono en la tabla y en el diagrama. Sale del mismo
calendario que dibuja el Gantt y no de una marca aparte: si fueran dos fuentes distintas podrían
decir cosas distintas, y el que mira le creería al icono. Una tarea es paralelizable si su tramo se
superpone con el de otra — lo cual ya está decidido por sus dependencias y por el repositorio en el
que corre.

**Los colores de estado pasaron a significar siempre lo mismo.** Rojo: se rompió. Verde: salió bien.
Azul: está pasando. **Naranja: hace falta una persona** — una decisión o una revisión, no "atención"
genérico. Estaban resueltos en tres lugares distintos y dos usaban el color primario del tema para
"corriendo", que según el tema salía violeta.

**El detalle de una tarea se rediseñó.** Los números —tiempo, costo, pasos, archivos, líneas— pasan
a una tira de cifras a lo ancho: estaban en renglones etiqueta/valor en la columna derecha, y eso se
lee en vez de barrerse. El tiempo lleva su estimación al lado, porque cuarenta minutos puede ser
rapidísimo o el doble de lo previsto.

Y las cuatro fechas se volvieron una **línea de vida**: creada → arrancó → terminó, con los dos
tramos escritos encima. Lo que importa no son los instantes sino cuánto esperó antes de arrancar
—que en una implementación larga suele ser más de lo que tardó en correr— y cuánto tardó; sueltas,
había que restarlas mentalmente.

## 67.0.0

**Retomar una implementación que falló ahora reintenta de verdad.** El motor sólo toma tareas
pendientes, y una tarea fallida ya no lo es: retomar no hacía nada, terminaba al instante y volvía a
mostrar el error de la vez anterior. No era que la pantalla mostrara información vieja — es que no
había pasado nada nuevo que mostrar.

Ahora, al retomar, las tareas fallidas vuelven a la cola con su error y sus tiempos limpios, y el
feed arranca en blanco: las líneas de la corrida anterior encima de las nuevas hacían imposible
saber si el "✗" que uno estaba leyendo era de ahora o de hace media hora. Las bloqueadas no se
tocan: esperan una decisión que nadie tomó, y relanzarlas las haría chocar contra la misma pregunta.

**Las enumeraciones de una descripción se ven en vertical.** Los modelos las escriben en línea —"hay
que 1) migrar la tabla, 2) exponer el endpoint, 3) cablear la pantalla"— y como párrafo corrido eso
se lee como una sola oración larga donde los números son ruido. Puestas una debajo de otra se leen
como lo que son: cosas distintas que hay que hacer, contables de un vistazo. La marca se conserva
tal cual venía —`1)`, `-`, `a)`— porque si el plan numeró, el número es parte del contenido y
alguien lo va a usar para referirse a un item.

Lo delicado no era partir sino no partir donde no hay lista:

- Hacen falta **al menos dos marcas**. Un "1)" solo es una aclaración, no una enumeración.
- Un número **entre paréntesis** no es un item. Los planes reales están llenos de `(mockup 01)` y
  `(tarea 13)`: el número va precedido y seguido de espacio, igual que un item, y sin mirar el
  paréntesis el texto se partía justo en el medio de una idea. Salió de probar el desarmador contra
  las descripciones que hay en la base, no contra ejemplos inventados.
- `v1.2` y `Art. 5.` tampoco.
- Los saltos de línea que ya estaban ganan sobre cualquier heurística: si alguien separó, esa
  separación es información real sobre cómo quiso que se leyera.

## 66.1.0

**Lanzar un análisis ahora se ve.** El botón está en el encabezado y el feed de actividad queda a
una pantalla de scroll, así que apretar "Analizar los documentos" no mostraba nada y parecía que no
había pasado nada. Ahora aparece una tarjeta arriba con lo que está haciendo en este momento y las
últimas líneas debajo — lo último dice qué pasa ahora, las de atrás dicen que viene avanzando y no
que se colgó en el primer paso. Los errores del análisis también se ven: antes se los tragaba.

Y el análisis dice qué documento está leyendo, uno por uno y con su tamaño. "Analizando 9
documentos" no deja ver que el que importaba entró vacío, que es la forma más callada que tiene esto
de fallar.

**Las dependencias son una columna de la tabla de tareas.** Estaban sólo debajo del título y sólo
mientras la tarea seguía pendiente, así que una vez hecha no quedaba forma de reconstruir el orden
que el plan había decidido.

**Treinta textos volvieron a estar en español.** Un bloque en inglés se había pegado dentro del mapa
español y, al ser el último, ganaba: `mapOf` se queda con la última definición sin decir nada. El
detalle de una tarea mostraba "pending", "Estimated", "Size", "code", "tests" con la app en
español. Había un bloque en español dentro del mapa inglés por el mismo motivo. Ahora hay un test
que falla si una clave se define dos veces en el mismo idioma: el compilador no dice nada y la
pantalla tampoco, así que tenía que decirlo alguien.

## 66.0.0

**Analizar los documentos antes de planificar sobre ellos.** Un plan no puede ser mejor que las
specs de las que sale, y lo que las specs no dicen el planificador lo inventa —bien, con seguridad,
sin marcarlo— así que el hueco aparece recién cuando el código está escrito y hace otra cosa. La
pasada busca lo que falta, lo ambiguo, lo que se contradice y lo que se está dando por sabido.

**No reescribe los documentos originales.** Deja uno nuevo al lado, con lo que se puede resolver
mirando el código y el resto de las specs, y con el resto **como preguntas abiertas**: resolver algo
de negocio inventando la respuesta es exactamente lo que la pasada viene a evitar. El documento
nuevo se suma a los que el planificador lee. Una spec es un acuerdo entre personas, no un borrador
de esta app: reescribirla en el lugar borraría lo que alguien redactó y acordó.

**Auditar el plan contra los documentos.** Planificar y verificar el plan son trabajos distintos, y
el que planificó es mal juez: para él el plan cubre todo, porque lo armó pensando eso. La auditoría
va **de los documentos al plan**, requisito por requisito, que es el único orden en el que se ve lo
que falta — yendo del plan a los documentos, lo que no está no aparece nunca, porque no hay ninguna
tarea que lo mencione. Marca lo que ningún task cubre, lo que sobra, lo que está fuera de orden y lo
que contradice la spec, y deja todo eso escrito como guía de replanificación. No cambia el plan
solo: replanificar tira las tareas y eso no puede pasar sin que alguien lo decida.

**El plan en el tiempo, como Gantt.** Una tabla ordenada por número contesta "qué falta"; no
contesta "por qué esta tarea todavía no arrancó" ni "cuánto de esto puede pasar a la vez". El
diagrama coloca cada tarea usando la misma regla que usa el motor para ejecutar —dependencias
cumplidas, un repositorio a la vez— porque un diagrama que muestra un orden distinto del que va a
pasar no es una previsión, es una ilustración. Para lo ya corrido usa lo que tardó de verdad; para
lo que falta, la estimación. Se redibuja con cada latido, así que la barra de la tarea en curso
crece sola.

Y una tarea planificada antes de que existieran los pasos ahora lo dice, en vez de mostrar el hueco:
sin eso parecía que la tarea no tuviera nada adentro.

## 65.0.0

**Pasadas de revisión sobre el código ya escrito.** Implementar y revisar son trabajos distintos, y
el modelo que acaba de escribir algo es el peor juez de ese algo: ya decidió que estaba bien. Una
pasada aparte, mirando el diff con otra intención —romperlo, no terminarlo— encuentra lo que la
primera no podía ver. Busca bugs, performance, diseño, arquitectura y tests faltantes, y **arregla
lo que encuentra**: una lista de problemas que nadie va a leer es trabajo tirado.

Cuántas pasadas es un rango y no un número, porque no se sabe de antemano cuánto hay para encontrar.
Se corre el mínimo siempre y se sigue mientras la anterior haya encontrado algo, hasta el máximo.
Cinco pasadas sobre código limpio son cinco corridas pagas para que digan "no encontré nada"; dos
sobre código con problemas se quedan cortas. Que el modelo pueda contestar "nada" es lo que hace que
el rango funcione, así que el prompt lo pide explícitamente — si no, inventa un hallazgo menor para
justificar la corrida y la serie nunca se corta.

Opcionalmente también después de cada tarea. Cuesta una pasada por tarea, pero encuentra el problema
cuando todavía es de una tarea sola: un bug que sobrevive cinco tareas ya tiene código encima que
depende de él, y arreglarlo pasa de ser un cambio de una línea a ser una discusión.

Cada pasada guarda qué encontró y qué arregló, que son dos números distintos y confundirlos arruina
los dos.

**El nombre de la rama se puede escribir a mano**, con un `Auto` que se lo deja a la IA. El nombre
elegido sobrevive a una replanificación: hace falta marcarlo aparte porque después de planificar
`branch` está lleno en los dos casos, y sin saber cuál fue una decisión de una persona se pisaba.

**El alta y la edición dejaron de ser un modal.** Se le fueron sumando decisiones —repositorios con
su rol y su rama base, documentos, la rama nueva, las pasadas— hasta que en una pantalla de catorce
pulgadas el botón de borrar un documento quedaba cortado por la mitad. Un modal sirve para una
pregunta; esto es un formulario con seis decisiones y cada una necesita explicarse.

Y ahora **los documentos se listan uno por uno**, con su tamaño, en vez de la ruta de la carpeta:
elegir un directorio y ver sólo su ruta era un acto de fe que se pagaba cuando el plan salía vacío.

## 64.0.0

**El plan ahora es un grafo, no una fila.** Las tareas que no dependen entre sí arrancan a la vez, y
terminar una destraba en cascada a las que la estaban esperando. `depends_on` ya existía, pero
servía sólo para saber a quién arrastraba una falla: el orden real era el número de tarea, así que
la 5 esperaba a la 4 aunque no necesitara nada de ella.

**Una sola tarea por repositorio a la vez.** Dos modelos escribiendo en el mismo árbol de trabajo se
pisan los archivos, y el commit que la herramienta hace al terminar una se llevaría puesto lo que la
otra dejó a medias. El paralelismo real aparece cuando la implementación toca varios repositorios
—el backend y el frontend avanzando juntos— que es justo el caso donde más se nota.

Las dependencias imposibles se ignoran en vez de honrarse: una que apunta a una tarea que no existe,
o hacia adelante en el plan. Las dos son errores de planificación, y respetarlas dejaría la tarea
esperando para siempre a algo que nunca va a llegar — una implementación trabada, sin nada roto y
sin nada que decir.

En la lista, cada tarea pendiente dice qué la está frenando. Sin eso, una tarea quieta en medio de
otras que avanzan parece salteada.

## 63.0.0

**Se puede elegir la base de datos: SQLite, PostgreSQL o MySQL.** SQLite sigue siendo la de fábrica
y sigue siendo el caso normal —un archivo, sin instalar ni configurar nada—. Esto es para quien
necesita otra cosa: una base compartida por un equipo, una que ya tiene backup, una que su
organización exige. Hasta ahora había que elegir entre eso y usar la app.

Toda la app está escrita en SQL de SQLite y va a seguir estándolo. La traducción al dialecto de cada
motor pasa en un solo lugar, en el borde, y es chica a propósito: las diferencias que importan son
un puñado —los tipos del DDL, el upsert, dos funciones de fecha, la concatenación— y están
enumeradas. Lo que no está enumerado no se traduce, porque un traductor que intenta entender SQL
arbitrario se equivoca en silencio, y una consulta mal traducida devuelve datos mal en vez de
fallar.

**Y se pueden mudar los datos de una base a otra.** Elegir motor sin poder mudarse no es una
elección: quien arrancó con SQLite —o sea, todos— tendría que empezar de cero, y eso significa
perder las reviews, las estadísticas y los hallazgos de meses.

La mudanza está hecha para no poder mentir:

- Se copia a una base vacía. Si el destino ya tiene filas, no se mezcla —mezclar dos bases con las
  mismas claves deja filas pisadas y un resultado que nadie puede verificar—.
- Se cuenta cada tabla de los dos lados y se comparan. Una copia que dice "listo" sin contar no vale
  nada: estos errores son silenciosos por naturaleza, y una tabla a medias parece completa hasta que
  alguien busca algo viejo.
- La app no se muda hasta que la copia cerró. Si algo falla, sigue usando la base de siempre, que
  queda intacta. La de origen no se borra nunca: es la red de seguridad.

Si la base configurada no responde al arrancar, la app abre la de fábrica igual y lo dice. Un
servidor caído no puede dejar sin arrancar a la app que contiene la pantalla donde se arregla la
conexión, pero arrancar con datos vacíos sin avisar se ve exactamente igual que haberlos perdido.

Hay tests que corren el esquema entero y las consultas que de verdad se traducen contra un
PostgreSQL y un MySQL reales, y uno que mueve datos de SQLite a PostgreSQL y los vuelve a leer del
otro lado, tokens cifrados incluidos. Un traductor de SQL probado sólo contra sí mismo no prueba
nada.

## 62.0.0

**Las tareas ahora tienen pasos.** El detalle de una tarea dice qué hay que lograr; los pasos dicen
cómo, y ahí se ve si el plan entendió el problema —una tarea de una línea puede esconder cinco
decisiones y eso no se nota hasta leerla desarmada—. Se guardan con el plan y se cierran con lo que
el modelo reporta al terminar: lo que no confirmó queda marcado sin hacer, no dado por bueno.

Se cierran al final y no mientras corre porque desde afuera no hay forma de saber en qué paso está:
los eventos del CLI dicen qué archivo tocó, no qué paso del plan estaba haciendo. Mostrar un avance
paso a paso sería una barra que no mide nada.

**El prompt de cada tarea queda guardado**, plegado al pie de su detalle. Es lo único que distingue
un problema del modelo de un problema de lo que se le pidió, y se escribe antes de correr: si la
tarea revienta a mitad de camino, guardarlo al final habría significado no tenerlo nunca en el único
caso donde importa.

**Las cuatro fechas de una tarea**: creada, modificada, arranque y fin. Creada y modificada contestan
si el plan se rehizo; arranque y fin, cuánto tardó. Con una sola no se puede distinguir una tarea
replanificada de una que nadie tocó.

**Revisión del plan.** El módulo sigue siendo autónomo —no hay que aprobar nada para que arranque—
pero autónomo no quiere decir que el primer plan sea el bueno, y hasta ahora ante un plan flojo sólo
quedaba dejarlo correr y arreglar después, o borrar todo y volver a cargar las specs. Ahora se
escribe en una línea en qué sentido corregirlo —"separá la tarea 4", "falta la migración"— y se
replanifica con el plan actual a la vista del modelo, no de cero: rehacer perdería las decisiones de
orden que ya estaban bien y devolvería otras distintas, y entonces no habría forma de saber si la
revisión mejoró algo o sólo barajó de nuevo. El prompt de revisión está a la vista para no escribir
una guía que repita lo que ya pide.

Replanificar reemplaza las tareas, así que si alguna ya corrió se avisa cuántas y se pide confirmar.
Los commits quedan en la rama; lo que se pierde es el registro de qué tarea los hizo.

## 61.2.0

- **El número de tarea es su propia columna**, primera de la tabla. Pegado al título se leía como
  parte del texto y no servía para lo que sirve un número de tarea: referenciarla —"la 3 depende de
  la 1"—.
- **La pantalla refleja siempre lo que está guardado.** Las tareas ya se persistían en cada
  transición, pero la vista dependía de que el motor emitiera una línea de log para enterarse: si
  una tarea cambiaba de estado en un silencio, había que salir y volver. Ahora un latido por segundo
  releé la base mientras algo corre, esté abierta la lista o el detalle de una tarea.

Hay tests que fijan que cada transición —arrancar, completar, fallar, bloquear, reintentar— quede
escrita: la pantalla lee de la base, así que lo que no se guarda no existe.

## 61.1.0

El detalle de tarea, rediseñado. Era una pila de cajas grises apiladas, todas del mismo peso: había
que leer prosa para llegar a un número y al revés.

- **Dos columnas.** A la izquierda **el relato** —qué se pidió, qué dice que hizo, qué falló, el
  commit—, que se lee de corrido. A la derecha **los hechos** —tiempo, costo, archivos,
  composición, señales—, que se barren de un vistazo.
- **El código va abajo y a lo ancho**, porque es lo único que necesita el espacio completo y es lo
  último que se mira: primero uno decide si vale la pena mirarlo.
- **El estado, grande y con su nombre**, arriba a la izquierda: decide qué significa todo lo demás.
- Los paneles ya no son cajas grises plenas —con cuatro apilados la columna se volvía un bloque
  uniforme— y el error se marca con una barra al costado en vez de un fondo relleno, así el texto
  queda legible.
- La composición del cambio pasa de anillo a **barra apilada**: en una columna angosta lo que
  importa es la proporción, no leer cada porción.

**Y una tarea planificada se lee como lo que es.** Sin commit, sin código y sin resultado, antes
quedaban tres secciones diciendo "todavía no". Ahora muestra lo que sí existe: la descripción
completa, cuánto se estimó, en qué repositorio va a correr y qué tiene que estar listo antes — que
es lo que uno mira para decidir si el plan tiene sentido.

## 61.0.0

El **Constructor**, rehecho: cómo se ve la lista, cómo se navega y qué se puede saber de una tarea.

### La lista es una tabla

Columnas de **estado**, nombre, repositorios, rama y avance, con **paginador** y **buscador** por
nombre, repositorio o rama. Con veinte implementaciones, las tarjetas obligaban a scrollear para
comparar dos cosas que en una tabla están una debajo de la otra. El buscador aparece recién con más
de cuatro y el paginador con más de una página: un control que siempre dice "1 de 1" ocupa lugar
para no informar nada.

### El detalle de una tarea es una pantalla, no una fila que se abre

Con quince archivos, expandir la fila empujaba el resto de la tabla fuera de la vista para leer algo
que igual no entraba. Ahora tiene su propia pantalla, y **navegación con camino** —Constructor ›
implementación › tarea— clickeable en cada escalón y **arriba de todo**. Un botón "volver" sirve con
un solo nivel; con tres hay que apretarlo dos veces y adivinar dónde cae.

### Qué se puede saber de una tarea

Además del código: **de qué se compone el cambio**, clasificando los archivos en código, tests,
migraciones, configuración, documentación y recursos. Y con eso, señales que responden lo que uno
se pregunta al revisar código escrito sin supervisión:

- **No tocó ningún test.** Trescientas líneas con tests y sin tests no son lo mismo, y el total no
  distingue.
- **El 80% del cambio está en un solo archivo.** Un archivo para leer entero no es lo mismo que una
  refactorización dispersa, aunque sumen igual.
- **Tocó migraciones o configuración.** Un error ahí no lo atrapa revisar la lógica, que es lo que
  uno hace por defecto.
- **Sólo cambió documentación.** Puede estar bien, pero conviene que se note.

Van en ámbar y no en rojo: no son errores, son cosas para mirar. Pintarlas de error haría que una
tarea correcta parezca rota y que el rojo deje de significar algo.

Más el **commit** con su mensaje, autor y fecha, y el aviso de que está **sólo en la rama local** —
la app nunca hace push, y decirlo evita que alguien lo busque en el remoto.

### El estado, con nombre

Cada tarea dice **pendiente, corriendo, completa, falló o esperando decisión**, en la tabla y en el
detalle. Un símbolo solo obliga a aprenderse cinco glifos, y "⏸" no dice por sí mismo que está
esperando una decisión.

### El modal de creación y edición

Era una fila de chips de repositorios y, debajo, otra mezclando roles con nombres de rama: todo del
mismo tamaño y color, sin decir qué era cada cosa. Ahora cada repositorio elegido es una tarjeta con
sus dos decisiones **etiquetadas y separadas** —qué es, y de qué rama parte—, con las ramas reales
del clon en un desplegable. Escribir un nombre de rama que no existe no falla al guardar: falla al
correr, media hora después.

## 60.0.0

Requerimiento nuevo: **ver el código que escribió el Constructor, como se lee una review**.

Saber que una tarea tocó cinco archivos no alcanza para confiar en ella: hay que ver qué escribió.
Es la diferencia entre un informe de actividad y algo que se puede revisar.

Ahora, al abrir una tarea, **cada archivo se abre y muestra su diff**: qué se agregó y qué se
cambió, con los números de línea de los dos lados, en verde y rojo. Es el **mismo renderizador que
usa la vista de código de las reviews**, así que el diff se lee igual venga de donde venga — y lo
que se aprendió arreglando esa vista vale acá sin reescribir nada.

- El diff se le pide a git **al abrir el archivo**, no se guarda: el commit ya lo tiene, guardar
  una copia sería duplicar el repositorio adentro de la base, y sólo se lee el archivo que alguien
  abre.
- Cada diff **scrollea adentro de su propio recuadro**: un archivo nuevo de mil líneas empujaría
  las otras tareas y el resto de la pantalla fuera de la vista.
- Los archivos siguen mostrando su letra —creado, modificado, borrado— y sus líneas, para poder
  decidir cuál abrir sin abrirlos todos.

## 59.0.0

Cuatro cosas en el **Constructor**, todas sobre lo mismo: poder ver y decidir lo que antes pasaba
en silencio.

### El detalle de cada tarea

La fila sólo se abría si la tarea ya había corrido, así que **un plan recién armado no se podía
leer** — justo cuando uno quiere ver qué se propone hacer antes de dejarlo andar. Y el `detail` que
escribe el planificador estaba guardado desde el principio **sin mostrarse en ninguna pantalla**.

Ahora toda fila se abre y muestra **qué hay que hacer** (lo que dice el plan), **qué hizo** (cuando
ya corrió) y **de qué depende**, que es lo que explica por qué una tarea está más abajo de lo que
uno esperaría. Tener las dos primeras juntas es la forma de ver si la tarea hizo lo que decía.

### De qué rama parte cada repositorio

Estaba fijo en la rama que el clon tuviera abierta de casualidad. Con varios repositorios es peor:
no todos usan el mismo nombre —`develop` en unos, `main` en otros— y adivinarlo hacía que la rama
nueva saliera del lugar equivocado sin que nada lo dijera hasta mirar el diff.

Ahora **se configura por repositorio**, eligiendo entre las ramas reales del clon —escribir mal un
nombre no falla al guardar, falla al correr— y **se ve en la implementación**: de dónde parte cada
uno y hacia qué rama va.

### Los cambios sin commitear se resuelven desde la pantalla

Antes se negaba a arrancar con un mensaje que ni siquiera decía **cuál** de los repositorios era el
del problema. Ahora lo nombra y ofrece **guardar en el stash** ahí mismo. Se puede ofrecer como
botón porque `git stash` no pierde nada: se recupera con `git stash pop`. Descartar no se hace
nunca, y los archivos nuevos entran en el stash — si quedaran afuera, el primer commit de la
implementación se los llevaría adentro.

### Los commits de la rama

Se pueden ver todos los commits que la implementación hizo, por repositorio, sin ir a la terminal.

## 58.0.0

Dos requerimientos nuevos en el **Constructor**: las tareas como tabla con su detalle, y poder
ajustar una implementación en marcha.

### Las tareas dejan de ser una lista de texto

Ahora son una tabla, con **tilde verde** en las terminadas y columnas que dicen lo que importa:
tiempo **real contra estimado**, archivos tocados separados en creados / modificados / borrados, y
líneas.

**Cada fila se abre** y muestra qué hizo esa tarea: el resumen, **la lista de archivos uno por uno**
con su letra y sus líneas, y el commit. Es lo que permite revisar sin abrir el repositorio.

Creados y modificados van separados a propósito: cuatro archivos nuevos son superficie nueva para
mirar entera, y dos modificados son un diff que leer. Un solo número de "archivos tocados" borra
esa diferencia.

Arriba de la tabla, el **esfuerzo invertido**: tiempo, consumo, archivos y líneas. Sólo cuenta lo
que quedó — una tarea fallida no dejó código, y sumar su esfuerzo diría que se produjo algo que no
está.

Los datos se toman del commit al terminar cada tarea y se guardan. Preguntarle a git en cada
apertura de pantalla sería una llamada por fila, y así sobreviven a que la rama se borre.

### Ajustar sin perder lo hecho

Se puede **editar** una implementación en marcha: sumar un repositorio que recién se abrió, corregir
un parámetro, agregar una spec que faltaba. Nada de eso puede obligar a empezar de cero.

Lo ya construido queda —su código está commiteado— y **rehacer el plan es una decisión aparte**: al
guardar se elige si se descartan las tareas pendientes o si el plan actual sigue. Replanificar solo
cada vez que se toca el título rehaco un plan de veinte tareas por nada.

## 57.1.0

El progreso del Constructor **se mueve mientras trabaja**.

Antes la barra sólo avanzaba cuando una tarea terminaba. Como una tarea puede llevar media hora,
entre medio no se movía nada y la pantalla parecía colgada justo cuando uno la mira para saber si
sigue viva.

- **Crédito parcial a la tarea en curso**: la barra avanza según lo que lleva corriendo contra lo
  que se estimó, **topeado al 90% de esa tarea**. Sin ese tope, una tarea que se pasa de su
  estimación empujaría la barra hasta dar por completo algo que no terminó — la forma más fácil de
  que una barra de progreso mienta.
- **Un reloj que corre**: los minutos transcurridos y lo que falta se actualizan solos cada
  segundo, y el "van N min" ahora incluye la tarea en vuelo.
- **La tarea en curso tiene su propia barra y su propio reloj**, contra su estimación, y se marca
  cuando se pasa. Es lo único que dice si *esta* tarea puntual se está yendo de largo.
- Las barras están **animadas**: el salto de una tarea a la siguiente se lee como movimiento y no
  como un parpadeo.
- La lista también late mientras hay algo corriendo; antes mostraba el avance del momento en que
  se abrió.

Una tarea sin estimación no finge avance: sin nada contra qué medir, inventarlo sería peor que no
mostrarlo.

## 57.0.0

**Arreglo del bug que rompió la primera implementación real**, más una auditoría de rendimiento del
código de los últimos días, el renombre del módulo y su portada.

### El modelo no existía

`HR-Ausencias` murió al arrancar con `[claude-code:unrecognized_model] {"model":"fable-5"}`. Yo
había inventado los identificadores `fable-5` y `opus-5`. Probado contra el CLI instalado: acepta
los alias cortos —`fable`, `opus`, `sonnet`, `haiku`— o el id completo `claude-fable-5`, pero
**`fable-5` no es ninguno de los dos**. El resto de la app ya usaba los alias cortos y yo rompí esa
convención. Hay un test que ahora lo fija, porque el modelo se valida del lado del servidor y el
error sólo aparece al correr.

### Rendimiento

- **Una escritura por tarjeta en cada dibujo de pantalla.** La foto diaria de cada repositorio se
  guardaba adentro de la lectura de la tarjeta: siete repositorios, siete `INSERT` por
  recomposición. Ahora se toma una vez al abrir la sección y fuera del hilo de interfaz.
- **Una consulta por implementación** para calcular su avance, dentro del bucle de dibujo. Ahora es
  una sola consulta agregada para todas.
- **Una consulta por trimestre** en la tabla de equipo y en la ficha individual — hasta ocho por
  cada fila dibujada. Ahora se agrupan en SQL de una vez.
- **Dos consultas por commit** al resolver identidades cuando había que unir dos personas: se
  traían dos listas enteras para comparar sus largos, sobre 2.283 commits. Ahora se cuentan en SQL.
- Una consulta a la base **por cada ticket de Jira** para elegir a qué sitio pedírselo.

### Corrección

- La consulta que elige la última review de cada PR se apoyaba en una particularidad de SQLite
  —`HAVING` con `MAX()` sobre una columna suelta—. Funcionaba, pero por accidente. Ahora es una
  subconsulta correlacionada, que dice lo que quiere decir.

### Además

- **Los errores se pueden copiar**, en la implementación y en cada tarea. Son literales del CLI que
  hay que buscar o pegar en otro lado —`unrecognized_model` fue exactamente eso— y transcribirlos a
  mano de una pantalla es donde se pierde el detalle que importa.
- El módulo pasa a llamarse **Constructor**, y su portada muestra lo que se mira al abrirla: qué
  hay corriendo, cuántas decisiones esperan respuesta —con el nombre de qué implementación las
  espera—, cuántas terminaron y cuánto se lleva consumido. Antes había que abrir una por una para
  descubrir cuál se había frenado.

## 56.0.0

Requerimiento nuevo: **una implementación puede abarcar varios repositorios**.

Se eligen los que hagan falta —no dos, los que sean— y cada uno declara su rol: **backend**,
**frontend** u **otro**. El rol se sugiere solo por el nombre (`-be`, `-fe`, `app`) y se cambia de
un click.

**El plan sigue siendo uno solo, y esa es la razón de ser de esto.** Con un plan por repositorio no
se puede ordenar lo cruzado: son dos listas que no se conocen y alguien termina coordinando a mano
cuál corre primero, que es justo el trabajo que este módulo viene a sacar. Con un plan único, el
planificador pone el endpoint antes de la pantalla que lo llama y hace que la segunda dependa de la
primera.

- **Cada tarea corre en un repositorio y lo dice.** Una tarea que toca dos son dos tareas con un
  contrato en el medio; escribir en dos a la vez haría imposible saber qué commit corresponde a qué.
- **La misma rama en todos**: buscar el trabajo de una implementación en tres repositorios con tres
  nombres distintos es un problema que no hace falta tener.
- **Se revisan todos antes de tocar ninguno.** Encontrar el segundo repositorio con cambios sin
  commitear cuando el primero ya se modificó dejaría el trabajo partido a la mitad.
- El rol no cambia cómo se ejecuta: le dice al planificador qué es cada repositorio, para que no
  proponga una pantalla en el backend ni una migración en el frontend.

## 55.0.0

Requerimiento nuevo: **módulo de implementaciones**. De las specs al código, sin supervisión.

Se le cargan los documentos —archivos `.md` sueltos o una carpeta entera, que se recorre— más un
prompt con lo que no está escrito ahí, y construye la feature completa.

**Dos modelos, y no por gusto.** Planifica con **Fable 5** porque el orden de las tareas es la
decisión que más cuesta deshacer: si la tercera necesita algo que recién aparece en la novena, la
implementación se traba a la mitad con medio trabajo hecho. Escribe el código con **Opus 5**, que
es un trabajo distinto, más largo y más repetitivo.

**Corre 100% autónomo**, con una sola excepción: **decisiones de arquitectura o de negocio que los
documentos no cubren**. Ahí no inventa — deja la tarea esperando, sigue con todo lo demás que
pueda avanzar, y pregunta con las alternativas que ve para que se conteste eligiendo. Adivinar una
de esas produce código que compila, pasa los tests y hace lo que no era. Todo el resto —nombres,
estructura, orden de los parámetros— lo decide solo: preguntar por eso lo volvería un cuestionario.

**Es lo primero de esta app que escribe código.** Todo lo demás corre Claude en sólo lectura. Por
eso trabaja en **su propia rama**, nunca en la de trabajo, se niega a arrancar si hay cambios sin
commitear, y **cada tarea que termina bien queda commiteada**: si la séptima falla, las seis
anteriores siguen ahí. Empujar al remoto no está permitido: eso lo decide una persona.

**Avance y estimación.** Cada tarea trae su tamaño y sus minutos estimados, y se mide lo que tardó
de verdad. Lo que falta se corrige con ese desvío: si las primeras tardaron el doble, lo que queda
también va a tardar el doble, y sostener la estimación original sería sostener un número que ya se
sabe malo. El desvío aparece recién cuando hay tareas terminadas — antes sería 1,0 y mostraría una
precisión inventada.

Una tarea que falla se **reintenta una vez** antes de darse por perdida: sin nadie mirando, una
caída pasajera frenaría todo hasta que alguien la mire, que es justo lo que no puede pasar.

## 54.0.0

Requerimiento nuevo: **varios Jira, agregados como se agregan los repositorios**.

La 53.0.0 asumía un solo Jira para todo. Los datos dicen otra cosa: los tickets de estos
repositorios salen de **tres familias de proyectos** —`KS`/`POS`, `CON`/`FIA`/`FIMA`/`TLOG` y
`FIS`— que son de clientes distintos y viven en instancias distintas. Con una sola configuración,
dos de las tres quedaban afuera.

- **Ajustes → Jira** pasa a ser una **lista**: se conectan los sitios que hagan falta, cada uno con
  su URL, su email y su token, y se editan o borran como los repositorios.
- Cada sitio declara **qué proyectos atiende** (`KS,POS`), y por ahí se rutea cada ticket. **Con un
  solo sitio conectado no hace falta declarar nada**: pedir esa lista cuando no hay ambigüedad es
  trabajo sin motivo.
- Si ningún sitio reclama un proyecto, no se pide a ninguno. Preguntarle a la instancia equivocada
  puede devolver un ticket que existe y no tiene nada que ver, y eso es peor que no traer nada.
- **Probar la conexión antes de guardar**, y devuelve el nombre de la cuenta: una credencial mal
  puesta se descubre ahí y no en la mitad de una review.
- Al editar, dejar el token vacío significa "no lo toqués" y no "borralo".

Los tokens se guardan cifrados con la misma clave que los del proveedor de git: son credenciales de
la misma clase.

## 53.0.0

Requerimiento nuevo: **integración con Jira**.

Se configura en Ajustes —URL, tu email de Atlassian y un API token— con un botón que prueba la
conexión y devuelve el nombre de la cuenta. Es **sólo lectura**: la app mira los tickets, nunca los
mueve.

**El ticket del PR se deduce solo**, de la rama y del título. Verificado contra los repositorios
conectados: `KS-655`, `feature/KS-631`, `FIS-389-auto-create-remediation`, `feat(POS-84): …`. Se
exigen mayúsculas para que ramas como `tasks-ms-rabbit-placeholders` o `feature/pos-ar-fiscal` no
den un falso positivo. **Gana la rama sobre el título**, porque la rama se crea al empezar y casi
no se toca mientras el título se edita a mano: hay un PR real con rama `KS-655` y título
`KS-644 history`, dos tickets distintos.

**El ticket entra en el prompt de la review**, que es lo que más cambia: permite revisar contra lo
que se pidió y no sólo contra el código. Un cambio impecable que resuelve otra cosa pasaba la
review sin que nadie lo notara; ahora eso se reporta como hallazgo **funcional**. Si el ticket es
ambiguo, la review lo dice en vez de inventar la intención.

Y se ve en la pantalla del PR, arriba de los hallazgos, con la descripción completa y no sólo el
título: el título dice de qué habla, la descripción dice qué había que hacer.

Los tickets se guardan al traerlos —el contenido casi no cambia mientras el PR está abierto— y se
piden a mano, no solos: es una llamada a un servicio externo por PR.

Sin Jira configurado, todo funciona exactamente como antes.

## 52.0.0

Requerimiento nuevo: **cada hallazgo dice qué clase de problema es**, no sólo cuán urgente.

La gravedad y la categoría son ejes distintos y hacían falta los dos. Para quien recibe el
comentario no es lo mismo "esto rompe la lógica de negocio" que "esto convendría resolverlo con
otro patrón", aunque los dos lleguen marcados como importantes: lo primero se arregla antes de
mergear y lo segundo se conversa.

Cuatro categorías, y no más —cada una de más es una decisión que el modelo puede errar—:

- **funcional**: cambia o rompe el comportamiento que el negocio espera.
- **bug**: defecto de código, se rompe con cierta entrada o en cierto estado.
- **diseño**: patrón, arquitectura, acoplamiento, buenas prácticas. Incluye lo que pidan las
  convenciones cargadas.
- **convención**: incumple una regla escrita en las guías del equipo.

**Con precedencia explícita**: si un hallazgo entra en varias gana la primera de esa lista, porque
algo que rompe el negocio se atiende como funcional aunque además sea un problema de diseño.

La categoría **viaja en el comentario publicado**, no sólo en la app: quien lo recibe lo lee en
Bitbucket, y ahí es donde tiene que poder distinguir un problema funcional de una sugerencia de
diseño. Va en español en el comentario, porque lo lee una persona.

Es obligatoria en el esquema de salida: si fuera opcional el modelo la omitiría en cuanto dudara y
la mitad de los hallazgos llegarían sin clasificar. Los hallazgos anteriores quedan sin categoría
en vez de recibir una adivinada a partir del título.

## 51.0.2

Arreglo: en la conversación, el avatar de **"Lo que preguntamos"** mostraba **"LP"**.

Las iniciales salían del propio rótulo del mensaje en vez de la persona que lo escribió. El rótulo
dice qué es el mensaje y el avatar dice quién lo escribió: son dos cosas distintas, y ahora van por
separado. El avatar usa el nombre con el que aparecemos en el proveedor, el mismo que ya se muestra
en las aprobaciones.

## 51.0.1

Ajuste: **las tarjetas de repositorio tienen todas el mismo tamaño.**

Antes cada una crecía según cuánto tuviera para mostrar —un repositorio sin deuda y sin historia
ocupaba bastante menos que uno con las dos cosas— y la grilla quedaba escalonada. Comparar de un
vistazo es justo para lo que sirve verlos todos juntos, y con alturas distintas eso se pierde.

Las acciones quedan ancladas al pie, así están a la misma altura en todas las tarjetas y el ojo no
tiene que buscarlas una por una.

## 51.0.0

Requerimiento nuevo: **cada repositorio con su estado, y el histórico para ver tendencias**.

Las tarjetas de Repositorios pasan de mostrar un nombre y dos contadores a responder la única
pregunta que uno tiene al abrir esa pantalla: **¿cuál me está esperando?**

**Deuda de revisión, arriba y sumada.** Respuestas sin contestar, hallazgos publicados que nadie
verificó y reviews terminadas sin publicar. Van juntas porque separadas cada una parece chica: en
esta instalación son **69 respuestas y 51 hallazgos sin verificar**, repartidos de a poco entre
cinco repositorios, y así no los veía nadie. Con una barra que muestra de qué está hecha esa deuda.

**Densidad, no totales.** Hallazgos por PR revisado y costo por PR, en vez de los acumulados: un
repositorio con más PRs revisados junta más hallazgos sin que eso diga nada de su código. Los
números reales de acá van de 23,2 hallazgos y US$ 58,59 por PR en un backend a 3,0 y US$ 0,92 en
otro — eso describe cómo se revisa cada uno, no cómo está programado.

**El PR más viejo, en días.** Nadie calcula de cabeza cuánto pasó desde una fecha, y menos con
siete repositorios.

**Y ahora se guarda historia.** Una foto por día y por repositorio, para poder contestar lo que un
número solo no puede: ¿esto se está acumulando o se está drenando? La tarjeta dibuja la evolución
de la deuda apenas hay dos días guardados. Una fila por día y no por cambio: dentro de una jornada
la deuda sube y baja con cada acción, y lo que interesa es dónde quedó.

## 50.0.0

Requerimiento nuevo: **escribir las convenciones directamente en la app**.

Ahora hay tres formas de darle criterios a la review, y conviven:

- **Subir un `.md`** — ya estaba.
- **Importarlo del repositorio** — ya estaba, desde la 37.0.0.
- **Escribirla acá**, en un editor con nombre y texto Markdown. Es la que faltaba y cubre el caso
  más común de todos: la regla que el equipo tiene clara y no está escrita en ningún lado.
  Obligar a crear un archivo para anotar "los controladores no llevan lógica" es pedir tres pasos
  —abrir un editor, inventar una ruta, volver— para dos renglones, y por eso no se hace.

Las escritas a mano **se pueden editar**: una convención se afina con el uso, y si no se pudiera
corregir habría que borrarla y reescribirla entera.

Las **importadas de un archivo no se editan desde la app**, a propósito: cambiarlas acá las dejaría
distintas del `.md` del que salieron y el próximo "actualizar" pisaría el cambio sin avisar. Esas
se editan en el archivo y se vuelven a importar.

El campo de texto es monoespaciado y alto porque lo que se escribe es Markdown que va a viajar
dentro de un prompt: verlo en una sola línea invita a escribir una sola línea. Y muestra el tamaño
contra el tope de contexto mientras se escribe.

## 49.0.1

Arreglo de la navegación de la 48.0.0: **la lista de repositorios ahora acompaña a toda la sección
de Repositorios**, no sólo al entrar a uno.

Antes aparecía recién al abrir un repositorio, así que la portada de la sección se veía sin lista y
el layout saltaba al hacer click. Entrar a la sección y entrar a un repositorio son el mismo lugar
y tienen que verse igual. En el Panel y en Estadísticas sigue sin aparecer.

## 49.0.0

Requerimiento nuevo: **un resumen de estadísticas que responda algo**.

Al armarlo apareció un problema de fondo en los datos, así que el resumen se diseñó alrededor de
él en vez de disimularlo.

**El hallazgo**: 40 commits —el **1,7%** del total— concentran el **69,5% de todas las líneas**.
Al abrirlos son importaciones de proyectos enteros: un `100k.json` de cien mil líneas, hojas de
estilo vendorizadas, librerías copiadas al repositorio. Con ellos adentro el reparto decía 77,9%
para una persona que sin ellos tiene 65,9%, y ponía a otra en el segundo puesto por dos commits de
importación. La métrica principal del módulo describía unos pocos movimientos de archivos.

El resumen ahora responde tres preguntas, en este orden:

1. **¿Se puede confiar en estos números?** Cobertura de autoría, identidades sin revisar y el
   aviso de commits atípicos con su porcentaje. Va primero porque si la respuesta es no, el resto
   sobra.
2. **¿Cómo se reparte el cambio?** Anillo por persona, calculado **sin** los commits de más de
   5.000 líneas, y diciéndolo. Seis porciones y el resto agrupado: con trece, ninguna se distingue.
3. **¿Qué encontró la revisión?** Anillo por gravedad, más cuántos hallazgos publicados siguen sin
   verificar contra el código —que es tan informativo como lo encontrado—.

Más la **actividad por trimestre**, lo único que muestra una tendencia y no una foto.

Sobre los gráficos: anillo y no torta, porque el agujero deja lugar al total y quita la tentación
de comparar áreas. Los porcentajes van escritos en la referencia, porque un ángulo no se lee con
precisión por bien dibujado que esté. Y sólo se usa para partes de un todo: para tiempos o
promedios sería un dibujo lindo sin significado.

## 48.0.0

Requerimiento nuevo: **cada sección con su propio lugar, y los repositorios como sección propia**.

La navegación estaba armada al revés: la lista de repositorios vivía pegada al costado en **todas**
las pantallas, y "agregar repositorio" era un ítem del menú principal, al mismo nivel que el Panel
o las Estadísticas.

- **Nueva sección Repositorios.** Cada uno es una tarjeta con lo que hace falta para decidir a
  cuál entrar: pull requests abiertos, cuántos se revisaron, si hay reviews corriendo y cuándo fue
  la última. Desde ahí se agrega uno nuevo y se entra a sus pull requests.
- **Agregar repositorio dejó de ser un ítem del menú**: es una acción de esta sección, no un lugar
  al que ir.
- **La lista lateral sólo aparece cuando trabajás con repositorios.** En el Panel o en
  Estadísticas no aportaba nada y se llevaba 260 dp de ancho útil.
- **"Personas" pasa a llamarse "Estadísticas"**, que es lo que la sección hace. Las personas son
  una de sus cuatro vistas, no el todo.
- **Estadísticas arranca en un resumen** con las tres cifras que resumen el estado —personas,
  commits leídos, pull requests— y, cuando falta cargar algo, lo dice con el botón al lado. Una
  tabla vacía sin explicación parece una función rota; el usuario no tiene por qué adivinar que
  primero hay que traer los datos.
- Sin repositorios conectados, la app abre directamente en Repositorios: es lo único útil que se
  puede hacer ahí.

## 47.0.1

Arreglo: **la reanudación de reviews interrumpidas nunca funcionó.**

Se descubrió mirando la base real: cinco reviews cortadas por cerrar la app y ninguna reanudada,
mientras la pantalla decía "se reanuda al abrir".

La causa es una secuencia que se muerde la cola. Al arrancar, la review que quedó corriendo se
encola como trabajo pendiente **y** acto seguido se marca fallida, con su mismo commit. El guard
que decide si hace falta reanudarla preguntaba "¿existe alguna review de este commit?" — y se
encontraba a sí misma. Contestaba que sí, descartaba el trabajo, y no quedaba rastro.

Ahora pregunta si existe una review **terminada**, que es lo que realmente significa "esto ya lo
hizo alguien". El caso legítimo sigue cubierto: si mientras la app estaba cerrada alguien corrió
la review a mano y terminó bien, no se repite. Y no hay riesgo de bucle, porque el trabajo se
saca de la cola pase lo que pase: como mucho un reintento por interrupción.

La función que shipeó la 33.0.0 recién ahora hace lo que decía. Los tests nuevos reproducen la
secuencia completa —encolar, marcar fallida, decidir— en vez de probar las piezas por separado,
que es como se había escapado.

## 47.0.0

Requerimiento nuevo: **leer sólo lo nuevo del historial de git**.

- La lectura ahora **continúa desde donde quedó**: guarda el commit hasta el que procesó cada
  repositorio y en la siguiente corrida sólo mira lo que llegó después. Hoy son 2.283 commits
  sobre siete repositorios en esta instalación, y releerlos enteros cada vez sólo empeora.
- **Si la historia se reescribió, vuelve a leer todo.** Cuando el commit anotado ya no está en el
  clon —un rebase, o el clon rehecho— continuar desde ahí dejaría un agujero en el medio del
  historial sin que nadie se entere.
- Botón aparte para **leer todo el historial**, que puede ser de años. No es el comportamiento por
  defecto porque son miles de commits y esa espera tiene que ser una decisión.
- La pantalla dice en qué modo corrió: "12 commits nuevos" y "12 commits leídos" no significan lo
  mismo, y sin aclararlo una corrida incremental parece una recolección que perdió casi todo.

**M3c (vueltas de conversación) queda descartada, con los datos a la vista.** De los 139 hilos
sincronizados, 59 tienen un comentario, 76 tienen dos y 4 tienen tres. Una métrica de "vueltas
promedio" sobre eso daría entre 1,4 y 1,6 para todo el mundo: no distingue nada. Se implementa el
día que las conversaciones sean más largas, si alguna vez lo son.

## 46.0.0

Requerimiento nuevo: **dónde se concentra el retrabajo**.

Nueva columna en la pestaña de Revisión: cuántos pull requests de cada persona necesitaron
correcciones después de que los revisamos, y cuántos commits en total.

- **Sólo cuenta si el PR tuvo hallazgos publicados.** Los commits que llegan después de una review
  que no encontró nada son desarrollo normal, no corrección: contarlos diría que alguien arregló
  algo que nadie le señaló. Es la condición que le da sentido al número.
- **"3 de 4" y no "3"**: se muestra siempre sobre cuántos PRs se pudo medir, porque el número solo
  significa cosas muy distintas si el denominador es 4 o 40.
- **No medido no es cero.** Si el commit que revisamos ya no está en la rama —hubo un rebase— el
  resultado es "no sé", no "no hubo correcciones". Mezclarlos haría que una historia reescrita se
  lea como un PR impecable.
- Se calcula al traer el histórico, que es cuando recién se conocen las ramas de los PRs, y se
  guarda: es una llamada a git por pull request y no tiene sentido repetirla en cada apertura.
  Volver a sincronizar no descarta lo ya medido.

Como todo en este módulo, alto no significa que alguien trabaje peor: puede ser un revisor
exigente, un requerimiento mal definido o un área intrínsecamente difícil. Es una señal para
preguntar, no una conclusión, y la pantalla lo dice.

## 45.0.0

Dos requerimientos nuevos: **menú vertical estilo VS Code** y **ocultar a quienes ya no están**.

### Barra de actividad

Los cuatro accesos —Panel, Personas, Repositorio, Info, Ajustes— pasan a una tira angosta de
íconos pegada al borde izquierdo, como la de VS Code.

- Antes vivían al pie de la lista de repositorios, compitiendo por el mismo ancho: con las
  etiquetas puestas no entraban y había que partirlos en dos filas de dos, con Ajustes recortado.
- Además separa dos cosas distintas: **navegar entre secciones** y **elegir en qué repositorio se
  trabaja**. Estaban mezcladas en la misma columna.
- Sólo íconos, con el nombre al pasar el mouse: a 48 dp no entra texto legible, y son pocas
  entradas y siempre las mismas, así que la posición se aprende enseguida.
- La marca de selección es una barra fina al borde, no un fondo relleno: con fondo, el ícono
  activo pesaría más que el contenido de la pantalla de al lado.
- Ajustes e Info quedan anclados abajo: no son destinos de trabajo, y arriba estarían a la misma
  altura que lo que se usa todo el día.

### Quienes ya no están

Se puede marcar a alguien como que dejó el equipo, y desaparece de los reportes.

- **No se borra nada.** En un trimestre viejo esa persona sí estuvo, y sacar su trabajo del
  histórico haría bajar los totales del equipo sin que nadie entienda por qué. Se filtra al
  mostrar, no al guardar.
- Hay un interruptor para volver a verlos cuando hace falta mirar hacia atrás, y archivar se
  deshace.
- Aplica a las tres pestañas: volumen, participación y hallazgos.

Esto además cierra una de las preguntas que la especificación había dejado abiertas — resultó que
la respuesta no era archivar *o* conservar el histórico, sino las dos cosas.

## 44.0.0

Requerimiento nuevo: **estadísticas por persona — ficha individual**.

Al hacer click en una persona se abre su ficha: sus números del período y, debajo de cada uno, la
lista que lo compone.

- **Todo número se puede abrir.** Si la ficha dice "8 pull requests, mediana 4 días", ahí mismo
  está cuáles fueron y cuál es el que tardó veinte. Un agregado que no se puede auditar sólo sirve
  para tener una impresión, que es lo que este módulo no debería producir.
- La lista va **completa y no un top**: el PR que explica la cola del percentil 90 puede ser
  cualquiera, y esconderlo dejaría el número sin la única fila que lo justifica.
- La **participación revisando va arriba**, con el mismo peso que el volumen. Al pie se leería
  como un apéndice, y es la mitad del trabajo.
- **Copiar como texto**: la ficha entera con período, listas y la advertencia, para pegar en una
  conversación. Una uno a uno se prepara con datos, no con capturas de pantalla — y una captura
  pierde el contexto justo cuando el número se discute.
- Si la persona tiene identidades unidas automáticamente, la ficha lo avisa: es la primera cosa a
  revisar cuando un número sorprende.

La ficha reemplaza la tabla en vez de abrirse en un modal: acá se viene a leer, y un diálogo
obligaría a cerrarlo para volver a mirar la lista.

## 43.0.0

Requerimiento nuevo: **gráficos en las estadísticas**.

Barras al lado de cada tabla, para comparar tamaños de un vistazo sin dejar de tener los números
exactos a la izquierda.

- **Volumen**: una barra por persona con agregado y borrado como tramos separados, no sumados. Un
  refactor que borra 2.000 líneas se vería como producción pura si se sumaran.
- **Evolución por trimestre**: barras verticales, porque el eje que importa ahí es el tiempo y el
  tiempo se lee de izquierda a derecha. El trimestre en curso sale más tenue: a mitad de camino
  siempre parece una caída si no se marca.
- **Hallazgos**: barra apilada por gravedad. Tres menores y tres bloqueantes son el mismo número y
  no son lo mismo, que es la única diferencia que importa ahí.
- **Pull requests**: mergeados, rechazados y todavía abiertos en la misma barra.

Dos decisiones que hacen que los gráficos digan la verdad:

- **Una sola escala para todas las barras.** Si cada fila se normalizara a su propio máximo, todas
  quedarían del mismo largo y el gráfico diría que todos hicieron lo mismo. Es la forma más fácil
  de mentir con barras, y hay tests que la cubren.
- **Sin eje numérico dibujado.** Los números exactos están en la tabla de al lado; un eje
  aproximado invita a leer cifras del gráfico cuando están escritas ahí mismo.

Los colores no salen del tema: el primario es azul y se usa para navegar, y reusarlo mezclaría dos
significados. Los pares que van juntos se distinguen por tono y por claridad a la vez.

Todo dibujado con el Canvas de Compose, sin dependencias nuevas.

## 42.0.0

Requerimiento nuevo: **estadísticas por persona — fase 4: histórico de pull requests**.

Botón para traer el histórico del proveedor, incluidos los cerrados y mergeados que sólo devuelve
si se los pide: son cientos por repositorio —148 contra 4 abiertos en uno de éstos— y por eso no
se descargan solos.

- **Cuántos PRs abrió cada uno** y en qué terminaron: mergeados, rechazados, abiertos.
- **Cuánto tardaron en cerrarse**, con mediana y percentil 90 y nunca promedio: un PR olvidado
  tres meses corre el promedio hasta que deja de describir a ninguno.
- **Tapa el agujero de la fase anterior.** La review no guardaba de quién era el PR y las métricas
  se calculaban sobre 7 de 12; el histórico completa esa autoría. Lo que ya se había guardado al
  correr la review no se pisa: es de primera mano, el histórico es una reconstrucción.
- Se pide un estado por vez y no los tres juntos: Bitbucket devuelve 401 al azar en cerca del 40%
  de las llamadas, y así un fallo cuesta un estado en vez de la corrida entera. Lo ya traído queda
  guardado.
- Un PR abierto no tiene fecha de cierre. Tomar su última actividad como cierre daría tiempos de
  ciclo de PRs que siguen vivos.

**Arreglo encontrado por un test**: el percentil 90 truncaba el índice, así que sobre cinco valores
devolvía el cuarto y el peor PR nunca aparecía — justo lo que ese número viene a mostrar, y con
equipos chicos fallaba siempre.

## 41.0.0

Requerimiento nuevo: **estadísticas por persona — fase 3: participación y hallazgos recibidos**.

Tercera pestaña en Personas, con lo que sale de nuestras propias reviews.

- **Participación revisando**: cuántos comentarios dejó cada uno y en cuántos PRs. Va primero y no
  al final a propósito — sin esto el módulo mide sólo a quien escribe código y trata como
  invisible a quien revisa, que es la mitad del trabajo. Lo que publicó la app en nuestro nombre
  no cuenta como participación de nadie.
- **Hallazgos recibidos** por el autor de cada PR, separados en bloqueantes, importantes y
  menores, normalizados por PR —quien mandó diez acumula más que quien mandó uno, y eso no dice
  nada de ninguno— y con los que no se resolvieron a la primera en columna aparte.
- Los nombres del proveedor se enganchan con las personas de git por nombre normalizado, y en
  estos repositorios alcanza: Bitbucket dice "Tomás Rivero" y git registra "Tomas Rivero". Quien
  comenta pero no commitea aparece igual, marcado, en vez de forzarlo dentro de la persona más
  parecida: eso le adjudicaría trabajo que no hizo.
- **La pantalla dice sobre cuánto está calculando.** La review no guardaba de quién era el PR: de
  12 revisados sólo 7 tienen autor conocido, y 205 de 264 comentarios están en PRs de los que no
  sabemos de quién eran. Se rellenó lo que se pudo y de ahora en más se guarda. Un número cierto
  presentado como si cubriera todo es una forma de mentir.

## 40.0.0

Requerimiento nuevo: **estadísticas por persona — fase 2: volumen de cambio**.

La sección Personas suma una pestaña con el volumen de cambio por persona, sobre las identidades
que resolvió la fase 1.

- Selector de período: **Todo**, últimos 3 meses, trimestre en curso, trimestre anterior. El
  trimestre en curso se marca como incompleto, para que no se lea como una caída cuando va por la
  mitad. Y **evolución por trimestre**, porque un total acumulado de dos años no describe a nadie.
- **Nunca un número solo**: agregadas, borradas, neto y tocadas, siempre juntas. Un refactor que
  borra 2.000 líneas suma más que el bugfix de una línea que salvó producción.
- Las líneas de archivos generados se muestran en su propia columna en vez de esconderse: son el
  25,6% del total, y sin verlas no se puede juzgar si la lista de exclusión está bien puesta.
- La tabla se puede ordenar, pero **no es un podio**: ordenar no destaca al primero ni le cambia
  el color. Y la advertencia de qué no se puede concluir está arriba, visible, no en un tooltip.
- Los bots quedan fuera de los totales.

**No hay métricas de pull request todavía, y es a propósito.** Medido sobre estos repositorios,
git ve 9 merges de PR en un año en `kubrik-erp-be` —donde la app conoce PRs hasta el #152—, 53 en
el frontend y 0 en timelogbook. Calcularlas desde git daría números que parecen reales y
subcuentan feo. Necesitan traer el histórico del proveedor, que es una fase aparte.

También: el recolector ahora tiene una prueba de integración contra un repositorio de git de
verdad. Los otros tests le daban salida fabricada, lo que prueba el parseo pero no que el comando
sea el correcto ni que el formato sobreviva al subproceso.

## 39.0.0

Requerimiento nuevo: **estadísticas por persona — fase 1: quién es quién**.

Nueva sección **Personas**. Todavía no muestra estadísticas, y eso es deliberado: primero hay que
resolver las identidades, porque cualquier número calculado antes está mal y encima parece bien.

- Medido sobre los siete repositorios conectados: la misma persona aparece hasta con **dos nombres
  y dos emails** —"Viktor K" con 223 commits y "Viktor Karpyuk" con 26 son la misma—. Agrupar por
  email parte a esa persona en dos; agrupar por nombre parte a quien commitea con y sin tilde.
- La resolución automática une por email y, si no, por nombre normalizado (sin tildes, sin
  mayúsculas). Sobre los 14 pares reales de nombre y email deja **9 personas**, y acierta casos
  que no eran obvios: "Tomás Rivero" y "Tomas Rivero" con emails distintos, o "Braian Chavez"
  commiteando desde dos dominios.
- Lo que ninguna regla puede resolver —dos cuentas con dos nombres, o un apodo como "Mapcky"— se
  **propone** para unir a mano, en vez de adivinarlo. Unir y separar son reversibles, y separar
  se lleva los commits de esa identidad: si no, la separación sería sólo cosmética.
- Una unión hecha por nombre queda **marcada**, porque es la que puede equivocarse: dos personas
  distintas pueden llamarse igual.
- Mientras haya identidades sin revisar, la pantalla avisa que los números serán **provisionales**.
- **El resultado no depende del orden en que se lean los repositorios.** Verificado contra los 25
  pares reales de nombre y email: una sola pasada dejaba a "Lautaro" partido en dos según qué
  commit apareciera primero, y en la base el `UNIQUE` descartaba el alias en silencio en vez de
  unir. Encontrar una identidad ya tomada no es un conflicto: es la prueba de que las dos personas
  son la misma.
- Las cuatro propuestas de fusión sobre datos reales —Viktor, Lautaro, Tobías y Juan— son todas
  correctas, y **no hay ninguna falsa**: nunca propone unir gente distinta.
- El historial se lee con un solo `git log` por repositorio —miles de commits, no mil invocaciones
  de git— y las líneas de archivos **generados se cuentan aparte desde el origen**: son el 25,6%
  del total, y sumadas harían que una semilla de datos pese más que un mes de trabajo. Las
  migraciones de esquema no se excluyen: son cambio real.

## 38.0.0

Requerimiento nuevo: **revisar sólo lo que llegó después de la última review**.

- Cuando un PR ya tiene una review terminada y llegaron commits nuevos, la corrida mira
  `últimoRevisado..head` en vez de la rama entera, y arrastra los hallazgos que quedaron
  abiertos: por cada uno el modelo dictamina si **sigue en pie**, si **se corrigió** o si **dejó
  de aplicar**, con la evidencia del diff.
- **Medido sobre la historia real del PR #149**: sus 21 re-reviews continuables releyeron 273.420
  líneas para mirar 11.819 nuevas. El trabajo de leer el diff baja al **4,3%**. Ese PR costó
  US$ 225, el 60% de todo el consumo de la app.
- **Vuelve a la pasada completa sola** cuando no puede continuar: si el autor rebasó o forzó el
  push, el commit revisado ya no está en la historia y el rango daría basura. Ante cualquier duda
  —git falla, el objeto no está en el clon— mira todo: una review completa de sobra cuesta plata,
  una incremental sobre una historia reescrita devuelve algo en lo que no se puede confiar.
- **Ningún hallazgo se pierde en el camino.** Si el modelo devuelve la lista de dictámenes
  incompleta, lo que no dictaminó se arrastra abierto igual. Un hallazgo que desaparece sin que
  nadie lo resuelva ni lo descarte es lo que una herramienta de revisión no puede hacer.
- Los hallazgos se **mueven** a la review nueva en vez de copiarse, así que conservan su id, su
  publicación y su ancla. Eso además corta de raíz la acumulación de filas muertas: de 87
  hallazgos sin publicar ni descartar, 81 eran residuo de reviews superadas.
- Botón **Revisar todo de nuevo** para pedir la rama entera, y la pantalla dice desde qué commit
  miró: una corrida que revisó sólo una parte tiene que decirlo.
- La pasada final sigue siendo siempre completa: es el contrapeso de encadenar incrementales.

## 37.0.0

Requerimiento nuevo: **importar las convenciones que el repositorio ya trae escritas**.

- Botón **Importar del repositorio** en la configuración de cada repo: busca los `CLAUDE.md` del
  clon local —raíz y hasta dos niveles— y los carga como guías. El módulo de guías se había
  shipeado con cero documentos mientras cuatro de los siete repositorios ya tenían el suyo
  escrito, uno de 23.000 caracteres a dos directorios de distancia.
- **El contenido se sigue guardando en la base, no se re-lee del archivo.** Es la decisión que ya
  había tomado la migración v34 y sigue en pie: si la review leyera el disco en cada corrida,
  cambiar de rama cambiaría las reglas de revisión sin que nadie se entere.
- Pero una copia congelada también envejece en silencio, así que se guarda la ruta y una huella
  del texto importado: cuando el archivo cambia, la guía aparece marcada como
  **desactualizada** con un botón para actualizarla. Nada cambia solo, nada queda viejo callado.
- Re-importar el mismo archivo actualiza la guía en vez de duplicarla —dos filas mandarían el
  mismo criterio dos veces al prompt— y respeta el interruptor: una guía apagada a propósito no
  se reactiva sola.
- Se saltean `node_modules`, `build`, `target` y compañía: en un monorepo son decenas de miles de
  directorios para encontrar tres archivos.

## 36.0.0

Requerimiento nuevo: **avisar antes de repetir una review que ya está hecha**.

- Al correr una review sobre un commit cuya revisión anterior **terminó bien**, la app avisa
  cuándo fue, qué encontró y qué costó, y deja decidir. No bloquea: repetir con otra profundidad
  o con guías nuevas es un uso legítimo.
- **Sólo avisa si la anterior terminó bien.** Si se había caído —15 de los 18 fallos históricos
  fueron por cerrar la app a mitad— se corre sin preguntar: ahí repetir es exactamente lo
  correcto y preguntar sería estorbar.
- Sale de medir la base local: 11 corridas repitieron un commit ya revisado, a 57, 127 y 139
  horas de distancia. No es alguien re-corriendo a propósito, es alguien que volvió al PR días
  después y no se acordaba. Lo que más pesa no es la plata sino los ~7 minutos de espera para
  redescubrir un resultado que ya estaba guardado.
- El modo automático ya salteaba los commits vistos; no se tocó.

## 35.0.0

Requerimiento nuevo: **novedades de cada versión dentro de la app**.

- La pantalla de Info suma una sección **Novedades** con lo que trajo cada versión, la instalada
  marcada, y links a esa versión y a todas las publicadas en GitHub.
- El changelog **viaja dentro del paquete**, no se descarga: la pantalla tiene que funcionar sin
  red, que es justo cuando uno mira qué cambió porque algo anda mal.
- El parseo es tolerante: si el archivo falta o el formato cambia, la sección muestra los links y
  nada más. Que una pantalla informativa rompa la app sería absurdo.
- Hay un test que exige que la versión que está corriendo figure en el changelog: si se bumpea sin
  anotar el cambio, falla.

## 34.0.0

Requerimiento nuevo: **subir convenciones y guías de arquitectura**.

- Se pueden subir documentos `.md` que la review tiene que respetar: cómo se nombran las
  interfaces, dónde va la lógica, qué patrón usa cada capa. Sin ellos la review marca como problema
  lo que es una decisión ya tomada, y ese ruido hace que se deje de leer lo que dice.
- **Dos alcances**: en Ajustes, las que valen para todos los repositorios; en el formulario de cada
  repositorio, las suyas. Las generales entran primero y las del repositorio después, y el prompt
  dice explícitamente que ante una contradicción manda la del repositorio.
- El prompt no se limita a incluirlas: aclara que son decisiones y no sugerencias, que **no** hay
  que reportar el código que las cumple, y que sí hay que marcar el que las contradice citando qué
  regla incumple.
- La pasada final también las respeta, para no terminar frenando un merge por una convención.
- Cada documento se puede desactivar sin borrarlo, y se ve su tamaño: hay un tope de 60.000
  caracteres porque el contexto no es gratis —una guía de cien páginas empujaría afuera el diff,
  que es lo que hay que revisar—. Si se recorta, se dice; callarlo sería peor.
- El contenido se guarda en la base y no como ruta a un archivo: con una ruta, mover o borrar el
  archivo cambiaría en silencio el criterio con el que se revisa.

## 33.0.0

Requerimiento nuevo: **retomar el trabajo que quedó a medias al cerrar la app**.

- Una review interrumpida ya no se pierde: se guardan sus parámetros —repositorio, PR, profundidad,
  tipo, modelo— y al abrir la app se vuelve a lanzar. Antes se marcaba fallida y ahí moría; había
  **16 así** en la base real.
- No es una reanudación literal y conviene decirlo: el subproceso de Claude Code murió con su
  contexto y no hay nada que retomar. Lo que se conserva es la orden, para volver a correrla igual.
- Se saltea sola si mientras tanto alguien ya revisó ese commit, o si el repositorio ya no está.
- Cuenta contra el mismo tope que el barrido: reanudar diez de golpe al abrir sería peor que
  haberlas perdido. Y corre aunque el automático esté pausado, porque es trabajo que ya pediste.
- Se abandona después de tres intentos: una review que revienta siempre —un binario roto, un repo
  que ya no está— reintentada en cada arranque sería un bucle que gasta plata y nunca termina.

Arreglo:

- **Las pestañas de Código y Commits habían desaparecido.** Al unificar el diálogo de merge en la
  32.0.0, el reemplazo se llevó por delante las dos ramas que las dibujan, así que desde esa
  versión ninguna de las dos mostraba nada. Restauradas, con el botón de volver a la conversación.

## 32.0.1

- Cada PR muestra las dos fechas con su etiqueta: **creado** y **actualizado**, con hora. La de
  actualización iba detrás de un "↻" que había que adivinar, y la de creación mostraba sólo el día
  sin la hora.
- Vale en la lista y en la cabecera del PR. Si nunca se actualizó desde que se creó, no se repite
  la misma fecha dos veces.

## 32.0.0

Requerimiento nuevo: **el diálogo de merge con la misma información que Bitbucket**.

- Ahora muestra **Origen**, **Destino**, **Estrategia**, **Mensaje del commit** editable y la opción
  de borrar la rama de origen, con el mismo orden y las mismas etiquetas.
- El mensaje viene prellenado con el formato exacto que usa Bitbucket al mergear —"Merged in
  <rama> (pull request #N)"— verificado contra los merges reales del repositorio, para que la
  historia se lea igual venga de donde venga.
- El diálogo **vive en un solo lugar**: se abría desde la lista y desde el PR con dos copias del
  mismo código. Duplicado, la que se tocara primero se llevaría las mejoras y la otra quedaría
  vieja sin que nadie lo note — y acá la diferencia entre las dos versiones sería un merge hecho
  con opciones distintas de las que uno creía.
- Lo que queda sin resolver se sigue enumerando ahí adentro. Desde la lista se arma con los
  conteos, que es lo que hay sin pagar una consulta por fila.

## 31.0.0

Requerimientos nuevos: **estrategias de merge** y **copiar el link del PR**.

- La confirmación de merge deja elegir entre las tres que ofrece Bitbucket: **commit de merge**,
  **squash** y **fast forward**. La elección se recuerda: en un equipo se usa casi siempre la misma.
- Qué estrategias están habilitadas lo decide el repositorio y la API **no lo publica** en ningún
  endpoint consultable —lo verifiqué contra el repo real—, así que se ofrecen las tres y el
  proveedor rechaza la que no corresponda. Es preferible a esconder una que sí estaba permitida.
- El mapeo a GitHub no es exacto y está dicho en el código: su `rebase` reescribe los commits sobre
  la punta del destino, que se parece a un fast-forward pero no es lo mismo. Se elige el más
  cercano.
- Ante un valor guardado inválido se cae en commit de merge, que es la que menos rompe: conserva la
  historia de la rama, mientras squash y fast-forward la pierden o la reescriben.
- **Copiar el link del PR** desde la pantalla del PR y desde cada fila de la lista, sin salir de la
  app —que es lo que uno hace para pegarlo en un chat o en un ticket—. Si todavía no cargaron los
  datos vivos, se arma con las coordenadas del repositorio en vez de no ofrecer nada.

## 30.0.0

Requerimiento nuevo: **mergear cuando quieras, sin tener todo resuelto**.

- El botón de mergear está **siempre habilitado** en un PR abierto. Mergear con cosas pendientes es
  una decisión legítima —una urgencia, comentarios que ya no aplican, un fix que no puede esperar—
  y la app no está para impedirla.
- Las seis condiciones dejan de bloquear y pasan a ser información: se siguen viendo al lado del
  botón, y **la confirmación enumera qué estás salteando** antes de mergear. Es el último momento
  en que sirve verlo.
- En la lista, el botón aparece en toda fila abierta. Cuando está todo resuelto va relleno; cuando
  falta algo, con contorno. La diferencia se ve sin leer, y el detalle está en la confirmación.
- El porcentaje de "listo" no cambia: sigue diciendo cuánto falta. Lo que cambia es que ahora es un
  dato y no una traba.

## 29.0.0

Requerimiento nuevo: **el círculo dice la postura, sin texto**.

- El color del círculo reemplaza al texto: **verde** aprobó, **rojo** pidió cambios, **gris**
  participa sin haberse pronunciado. Con varias personas, el texto al lado ocupaba toda la fila.
- Para poder mostrar el gris hubo que guardar también a los participantes que **no** se
  pronunciaron, que antes se descartaban.
- El nombre y la postura aparecen al pasar el mouse: el color se reconoce de reojo, pero hay que
  poder confirmar sin adivinar.
- Se sacó el badge "aprobado por X" de la lista: los círculos ya lo dicen, y repetirlo tapaba el
  estado de la review, que es lo otro que hay que ver en esa fila.
- **Un participante sin postura nunca cuenta como aprobación.** Es el riesgo de guardarlos: si
  contara, el barrido dejaría de revisar un PR que nadie aprobó. Las reglas leen sólo a quienes se
  pronunciaron, y hay un test que lo fija.

## 28.0.1

- Los círculos de persona pasan al doble de tamaño: 36 puntos en las filas y 44 en la pantalla del
  PR.
- Las iniciales y el borde crecen con el círculo. Unas iniciales chicas dentro de un círculo grande
  se ven perdidas, y son lo que se lee cuando el color no alcanza para distinguir.

## 28.0.0

Requerimientos nuevos: **cerrar respuestas sin contestarlas** y **círculos por persona**.

- Una respuesta se puede dar por **cerrada sin contestar**, y hay un botón para cerrar todas las de
  un PR de una vez. No toda respuesta pide una contestación —"corregido", "gracias", "dale"— y sin
  una salida esas bloqueaban el merge para siempre: había 24 así en un solo PR, y la única forma de
  destrabarlo era escribir algo que nadie necesitaba leer. Es reversible.
- Una respuesta cerrada deja de contar como pendiente en el panel, en la conversación y en la
  condición para mergear.
- **Círculos con las iniciales de cada persona**, con su nombre completo al pasar el mouse. Están
  en la lista de PRs —el autor y quiénes se pronunciaron—, en la pantalla del PR, en cada mensaje
  de la conversación y en el historial.
- El borde del círculo dice qué opinó: verde aprobó, rojo pidió cambios. Con varias personas, una
  lista de nombres ocupa toda la fila; los círculos entran en el ancho de un badge.
- El color de cada persona es estable entre aperturas: si cambiara, el círculo dejaría de servir
  para reconocer a alguien de un vistazo, que es lo único que hace.
- Detalle que encontró un test: con una paleta de ocho colores y `hashCode()`, el equipo real de
  cinco personas caía en **tres** colores —esos nombres comparten prefijos y longitud, que es justo
  lo que ese hash agrupa—. Con doce colores y FNV, los cinco quedan distintos.

## 27.0.0

Requerimientos nuevos: **cabecera redimensionable** y **pantalla completa**.

- La línea que separa la cabecera del PR —acciones, porcentaje, verificación— del contenido se
  puede **arrastrar arriba y abajo**, hasta dejarla en cero. Cuánto espacio merece cada zona
  depende de qué estés haciendo, y por eso lo decide quien mira y no el layout. La altura se
  guarda.
- Botón de **pantalla completa**: esconde todo lo de arriba de un click y le da el alto entero al
  contenido. Otro click vuelve.
- La tarjeta del **porcentaje también se pliega**, como las otras dos. Plegada sigue diciendo lo
  único que se mira de reojo —"85% · todavía no está listo"— y esconde el detalle de qué falta.

Arreglo:

- **Tus acciones se guardaban como "nosotros" en vez de con tu nombre.** Eso creaba una persona
  fantasma: la misma aprobación figuraba dos veces, una registrada por la app y otra como "Viktor
  Karpyuk" cuando el sync la traía de la API —así estaba en la base, con las dos filas—. Ahora se
  usa el nombre con el que el proveedor te nombra, sacado de los comentarios que ya sabemos
  tuyos, y una migración unifica lo ya guardado.
- Por eso ahora dice "aprobado por Viktor Karpyuk" también cuando la aprobación es tuya.

## 26.0.0

Requerimiento nuevo: **pedir cambios, y el mismo control que da Bitbucket**.

- Los botones ahora reflejan lo que ya dijiste, en vez de ofrecer siempre lo mismo:
  - Sin opinar: **Aprobar** · **Pedir cambios**
  - Ya aprobaste: se ve "lo aprobaste" y quedan **Retirar aprobación** · **Pedir cambios**
  - Pediste cambios: se ve "pediste cambios" y quedan **Aprobar** · **Retirar el pedido**
- Ofrecer "Aprobar" a quien ya aprobó no dice nada y esconde la acción que sí sirve.
- Las dos posturas son **excluyentes**, como las modela Bitbucket en `participants[].state`:
  approved, changes_requested o nada. Pedir cambios después de aprobar reemplaza, no suma.
- Se registran también las posturas de los demás: al abrir un PR se ve quién aprobó y quién pidió
  cambios. Verificado contra la API: en el `#151` ya figuraban dos aprobaciones, una tuya.
- Retirar la propia no toca la de los otros.
- En GitHub, "retirar el pedido de cambios" no existe como operación —una review enviada no se
  borra— así que se resuelve con lo más cercano, igual que ya se hacía con la aprobación.

## 25.0.3

- **Los botones de verificar, aprobar, declinar y mergear ya no desaparecen.** Se escondían por
  completo cuando no cargaban los datos vivos del PR —algo común con el 401 intermitente de
  Bitbucket— o cuando el PR todavía no tenía review. Esconderlos hacía imposible saber si la
  función existía.
- Si falta el dato vivo del PR, ahora se dice por qué y hay un botón para **reintentar**. Antes la
  única forma era salir de la pantalla y volver a entrar.
- Aprobar y declinar aparecen aunque no haya review: no la necesitan. Mergear la sigue exigiendo,
  pero eso lo dice su propia condición en vez de hacer desaparecer los cuatro botones.
- Si el PR está cerrado o mergeado se dice, en vez de mostrar una zona vacía.

## 25.0.2

- **Un PR cuya review no encontró nada ya se puede mergear.** La condición "tiene que haber commits
  desde la review" se exigía siempre, incluso cuando no habíamos publicado ni un comentario: si no
  pedimos ningún cambio no hay nada que corregir, y exigir un commit dejaba ese PR sin poder
  mergearse **nunca**. Es el caso de `talos-apirest #1517`, con cero hallazgos y todo lo demás en
  orden.
- El porcentaje de "listo para mergear" ya tenía esa condición bien; el botón no. Eran dos reglas
  que debían coincidir y no coincidían: el mismo PR figuraba al 100% y con el botón deshabilitado.
  Ahora la excepción está en la regla compartida y hay un test que la fija.

## 25.0.1

- Las tarjetas de la pantalla del PR —porcentaje de listo, pasada final, verificación— se **pliegan
  y tienen scroll propio**. El resumen de la pasada final del PR #149 son 3.728 caracteres: en una
  tarjeta rígida eso empujaba hacia abajo la verificación, las pestañas y todo el contenido, y para
  llegar abajo había que scrollear la pantalla entera pasando por un muro de texto.
- La pasada final y la verificación **arrancan plegadas**, pero su conclusión sigue a la vista en
  el título: "nada que frene el merge", "2 bloqueantes", "quedó vieja". Se ve el veredicto sin el
  texto completo, y se abre lo que interesa.
- El porcentaje queda siempre visible —es el titular— y lo que se acota es la lista de lo que
  falta, que además ya no se corta en seis: se ven todas con scroll.
- El plegado de cada tarjeta se recuerda entre arranques.

## 25.0.0

Requerimientos nuevos: **autor visible**, **aprobación persistida** y **secciones plegables**.

- En la lista de PRs el autor va con etiqueta y color propio —"autor: Nombre Apellido"— en vez de
  mezclado entre las ramas y la fecha. Es el dato que uno busca al barrer la lista.
- **Las aprobaciones se guardan, y un PR aprobado ya no se revisa.** La aprobación es la señal de
  que se terminó de mirar; seguir revisándolo gasta una corrida del modelo en algo ya decidido. El
  barrido lo saltea diciendo quién aprobó.
- Se registran tanto las nuestras —al aprobar desde la app— como las de otros, que se ven al abrir
  un PR. El listado de Bitbucket **no** trae las aprobaciones: sólo el PR individual, en
  `participants`. Pedirlo por fila sería una llamada de red por PR, así que se guardan localmente
  y la lista lee de ahí en una sola consulta.
- Retirar una aprobación la borra, para que el PR vuelva al circuito. La propia nunca se borra por
  un sync vacío: la conocemos de primera mano y la API puede tardar en reflejarla.
- Las secciones del panel se **pliegan** y cada una tiene su color: rojo para "te respondieron",
  verde para lo cerrado, azul para lo que está en curso. Con seis listas del mismo gris, encontrar
  una era scrollear y leer. El plegado se guarda.

## 24.0.0

Requerimiento nuevo: **el recorrido señala el comentario, no sólo la línea**.

- "Anterior" y "Siguiente" ahora resaltan la **tarjeta del comentario** al que apuntan: borde del
  color de su gravedad y fondo marcado. Antes sólo se resaltaba la línea, y en un archivo con
  varios comentarios no se distinguía de cuál se estaba hablando —que es exactamente el caso que
  más se da.
- La tarjeta activa lleva su posición, "3 de 7", así se sabe dónde estás parado sin mirar la barra
  de arriba.
- Vale igual para los hallazgos de la review y para las notas propias, que comparten el recorrido.

## 23.0.0

Requerimiento nuevo: **cada hallazgo puede proponer cómo se resuelve**.

- Además de señalar el problema, la review propone el arreglo: concreto, mínimo, coherente con el
  código de alrededor, y en un bloque de código cuando corresponde. Señalar sin proponer deja todo
  el trabajo de pensar la solución del otro lado.
- La propuesta **va en el comentario que se publica**, no sólo en la app: el valor de proponer cómo
  se arregla es que lo lea quien tiene que arreglarlo.
- Se muestra en bloque aparte —fondo propio, monoespaciada— porque son dos cosas distintas: el
  cuerpo dice qué está mal y esto dice qué hacer. Mezclados, la propuesta se pierde justo cuando es
  lo más accionable.
- **El campo es opcional a propósito.** El prompt pide dejarlo vacío cuando no se puede sostener
  una propuesta, y el esquema no lo exige: si fuera obligatorio, el CLI reintentaría hasta que el
  modelo invente una. Una sugerencia inventada es peor que ninguna.
- Cuando la decisión depende de contexto que la review no tiene —una regla de negocio, una
  preferencia del equipo— lo dice en el cuerpo y no propone.

Además: se agregó la especificación del módulo de estadísticas por persona en
`docs/specs/`, con casos de uso, métricas definidas y decisiones tomadas. Sin implementar.

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
