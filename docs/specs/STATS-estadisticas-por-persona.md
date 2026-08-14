# STATS — Estadísticas por persona

Estado: **fase 1 implementada (v39.0.0, 2026-08-14)** · fases 2–5 pendientes
Fecha: 2026-08-12 · decisiones cerradas 2026-08-12 · fase 1 verificada contra datos reales 2026-08-14

---

## 0. Decisiones tomadas

| # | Decisión | Consecuencia |
|---|---|---|
| D1 | **Uso personal**: lo ve sólo quien opera la app | Las advertencias de §2 quedan, pero como nota en el encabezado y no como bloque a pantalla completa. La exportación es para notas propias |
| D2 | **Los commits sin PR se cuentan, en columna aparte** | Volumen de cambio describe todo el movimiento; se ve cuánto pasó por revisión y cuánto no |
| D3 | **Lista de exclusión propuesta por defecto, ajustable por repositorio** | Ver §5.M2. Medida sobre datos reales: excluye 25,6% de las líneas |
| D4 | **Período libre, con histórico completo y corte trimestral** | Ver §6.UC-7 y §8. No hay "período por defecto" fijo: hay presets y rango |
| D5 | **Bots**: el mecanismo existe, sin entradas precargadas | Medido: hoy no hay ninguna dirección de bot en estos repos. Se marca a mano si aparece |
| D6 | **El clon se actualiza solo antes de calcular** | `git fetch` de la rama principal, igual que antes de cada review. Sin eso las métricas describen un repo viejo |

## 1. Propósito

Responder preguntas sobre cómo circula el trabajo por el equipo: quién abre más pull requests,
dónde se concentra el retrabajo, qué volumen de cambio mueve cada uno, cuánto tarda una revisión
en cerrarse y en qué parte del circuito se traba.

Dos niveles:

- **Agregado**: comparar el equipo en un período.
- **Individual**: la ficha de una persona, con su detalle y su evolución.

## 2. No-objetivos

Esto importa tanto como el propósito, porque define qué NO se puede concluir de los números.

- **No es una herramienta de evaluación de desempeño.** Ninguna métrica de acá mide qué tan bien
  programa alguien. Un módulo que se lea así va a ser usado así, y las decisiones que se tomen con
  esos números van a ser malas.
- **No sirve para comparar personas que hacen trabajos distintos.** Quien mantiene un módulo
  heredado y quien arranca uno nuevo no son comparables en ninguna de estas métricas.
- **No mide calidad.** Cero hallazgos en un PR puede significar que estaba impecable o que la
  review fue superficial.
- **No es un ranking.** La UI no debe presentar un podio ni un "top". El orden es una herramienta
  para encontrar algo, no un veredicto.

Cada pantalla debe llevar esta advertencia visible, no enterrada en un tooltip.

## 3. El problema cero: identidad

**Sin resolver esto, todas las métricas están mal.** No es un detalle de implementación.

Medido sobre `kubrik-erp-be`, últimos 90 días:

| Nombre en git | Email | Commits |
|---|---|---|
| Viktor K | viktor@kubriksoftware.com | 142 |
| Mateo Andrés Perano | mateo@kubriksoftware.com | 32 |
| Santiago Del Percio Rodriguez | santiago@kubriksoftware.com | 18 |
| **Viktor Karpyuk** | **viktor.karpyuk@kubriksoftware.com** | **13** |
| **Mateo Andres Perano** (sin tilde) | mateo@kubriksoftware.com | 9 |
| Braian Chavez | braian@kubriksoftware.com | 6 |
| Tomas Rivero | ttomasrivero@gmail.com | 1 |

Tres problemas distintos, todos presentes hoy:

1. **Un mismo email, dos grafías del nombre.** Mateo aparece con y sin tilde. Agrupar por nombre
   lo parte en dos personas (32 y 9).
2. **Una misma persona, dos emails.** Viktor commitea con dos direcciones. Agrupar por email lo
   parte en dos (142 y 13).
3. **El nombre del proveedor no es el de git.** Bitbucket muestra "Tomás Rivero"; git registra
   "Tomas Rivero <ttomasrivero@gmail.com>". Un PR y sus commits quedarían en personas distintas.

### Decisión

Se introduce una entidad **Persona** con **identidades** asociadas. Una identidad es un par
`(fuente, valor)`:

- `GIT_EMAIL` → `mateo@kubriksoftware.com`
- `GIT_NAME` → `Mateo Andrés Perano`
- `FORGE_USER` → el `display_name` que devuelve Bitbucket/GitHub

Resolución automática al descubrir una identidad nueva:

1. Si el email coincide con una identidad existente → misma persona.
2. Si no, si el nombre **normalizado** coincide (minúsculas, sin tildes, espacios colapsados) →
   misma persona.
3. Si no → persona nueva.

Y **siempre** una pantalla de fusión manual: la heurística no puede resolver el caso de Viktor con
dos emails y dos nombres distintos, y ninguna heurística razonable puede. Fusionar y separar tienen
que ser reversibles.

**Regla dura**: si una persona tiene identidades sin confirmar, sus números se muestran marcados
como provisionales. Es preferible un número con asterisco a un número limpio y equivocado.

## 4. Fuentes de datos y sus límites

| Fuente | Qué aporta | Límite |
|---|---|---|
| `pr_cache` | PRs abiertos: autor, fechas, ramas | **Sólo los abiertos.** El histórico requiere el fetch explícito que ya existe |
| API del proveedor | PRs históricos, estado, fechas de merge | 148 históricos en un repo; se pagina, y el 401 intermitente ya conocido |
| Clon git local | Commits, autor, email, fecha, líneas +/− por archivo | Refleja la rama que se haya traído; un rebase reescribe autoría y fechas |
| `review`, `finding` | Hallazgos por PR, gravedad, veredicto de resolución | Sólo de PRs que revisamos con la app |
| `pr_comment` | Quién comentó y cuándo | Sólo de PRs sincronizados |

**Consecuencia**: las métricas se dividen en dos familias que no se pueden mezclar sin aclararlo.

- **Cobertura total**: derivadas de git y de la API (todos los PRs del período).
- **Cobertura parcial**: derivadas de nuestras reviews (sólo los PRs que la app revisó).

Una pantalla que sume las dos sin decirlo miente. Cada métrica declara su familia.

## 5. Métricas

Para cada una: definición exacta, qué la distorsiona, y qué **no** se puede concluir.

### M1 — PRs abiertos

**Definición**: cantidad de PRs cuyo autor es la persona, con `created_on` dentro del período.
**Familia**: cobertura total.
**Distorsiones**: quien parte el trabajo en PRs chicos suma más que quien manda uno grande; eso es
una preferencia de estilo, no de productividad. Los PRs automáticos (bots, dependabot) inflan.
**No concluir**: que quien abre más PRs produce más.

### M2 — Líneas de código

**Definición**: suma de `added` y `deleted` de los commits de la persona en el período, **excluyendo
archivos generados o de datos**.
**Familia**: cobertura total. Incluye commits empujados directo a la rama principal (D2), contados
aparte de los que llegaron por pull request.

**Lista de exclusión por defecto** (D3), ajustable por repositorio:

```
**/seed/**                  datos sembrados
tools/**                    prototipos y utilidades con datos
**/fixtures/**  **/testdata/**  **/__snapshots__/**
package-lock.json  yarn.lock  pnpm-lock.yaml
**/dist/**  **/build/**  **/node_modules/**
*.min.js  *.min.css
*.png *.jpg *.jpeg *.gif *.svg *.ico *.pdf *.woff *.woff2 *.ttf *.zip *.jar
```

Medida sobre `kubrik-erp-be`, 90 días: de 502.044 líneas totales excluye **128.275 (25,6%)** y deja
373.769 de código real. Lo que infla ahí no son las migraciones sino `db/seed/**` —un archivo solo
aporta 40.505 líneas— y `tools/ai-catalog-import-prototype/**` con 38.000 en `.json` y `.csv`.

**`db/migration/**` NO se excluye**: son 14.615 líneas de cambio de esquema, que es trabajo real y
hay que revisarlo. La distinción entre migración y semilla es la que importa, no la extensión.
**Distorsiones**: es la métrica más fácil de malinterpretar de todas. Un refactor que borra 2.000
líneas suma más que un bugfix de una línea que salvó producción. Formateo automático, renombres
masivos y movimientos de archivo inflan sin aportar nada.
**No concluir**: nada sobre valor entregado. Sirve para dimensionar el volumen de cambio que hay
que revisar, no el aporte de quien lo escribió.
**Se muestra siempre desglosado** en agregado / borrado / neto, nunca como un número solo, y
siempre con el conteo de archivos generados que se excluyeron.

### M3 — Retrabajo

Es la métrica que más hay que definir con cuidado, porque "retrabajo" puede significar cosas muy
distintas. Se descompone en tres, medidas por separado:

- **M3a — Commits de corrección tras la review.** Commits del autor posteriores al commit que se
  revisó, en un PR con hallazgos publicados. Es lo que la app ya sabe calcular
  (`commitsSinceReview`).
- **M3b — Hallazgos que no se resolvieron a la primera.** Hallazgos publicados cuyo veredicto dio
  `PARTIAL` o `UNRESOLVED` en la primera verificación posterior. **Familia: cobertura parcial.**
- **M3c — Vueltas de conversación.** Cantidad de intercambios en un hilo antes de cerrarse. Un hilo
  de seis mensajes puede ser un desacuerdo sano; no es automáticamente malo.

**Distorsiones**: alto retrabajo puede significar código flojo, pero también un revisor exigente,
un requerimiento mal definido, o un área del sistema intrínsecamente difícil. Es una señal para
preguntar, no una conclusión.
**No concluir**: que quien tiene más retrabajo trabaja peor.

### M4 — Tiempo de ciclo

**Definición**: de `created_on` a la fecha de merge, en días. Se reportan **mediana y percentil 90**,
no promedio: un PR abandonado tres meses corre el promedio y no describe a ninguno.
**Familia**: cobertura total.
**Distorsiones**: incluye tiempo de espera del revisor, no sólo del autor. Un PR lento puede ser
culpa de quien no lo revisó.
**Descomposición**: se reporta aparte *tiempo hasta la primera review* y *tiempo desde la última
corrección hasta el merge*, para separar espera de autor de espera de revisor.

### M5 — Gravedad de lo encontrado

**Definición**: hallazgos por PR de la persona, agrupados por bloqueante / importante / menor.
**Familia**: cobertura parcial.
**Distorsiones**: depende de la profundidad de review configurada; un repo en LIGHT produce menos
hallazgos que uno en HEAVY, y eso es del repo, no de la persona.
**Se normaliza** por PR y se muestra la profundidad usada.

### M6 — Participación en revisiones

**Definición**: comentarios que la persona dejó en PRs **de otros**, y respuestas a comentarios que
recibió.
**Familia**: cobertura parcial.
**Por qué está**: sin esto el módulo mide sólo a quien escribe código, y trata como invisible a
quien revisa. Es la métrica que evita que el resto se lea como "producir = commitear".

---

## 6. Casos de uso

### UC-1 — Resolver identidades antes de mostrar cualquier número

**Actor**: quien usa la app.
**Precondición**: hay al menos un repositorio conectado con clon local.
**Disparador**: primera vez que se abre el módulo, o cuando aparece una identidad nueva.

**Flujo**:
1. El sistema recorre los commits del período y los PRs conocidos, y extrae identidades.
2. Aplica la resolución automática (§3).
3. Presenta las personas resultantes con sus identidades, marcando las que agrupó por nombre
   normalizado y no por email.
4. La persona puede fusionar dos, separar una identidad mal asignada, o marcar una como bot.
5. Confirma.

**Criterios de aceptación**:
- CA-1.1 Mateo con y sin tilde, mismo email, aparece como **una** persona sin intervención.
- CA-1.2 Viktor con dos emails distintos aparece como **dos**, marcadas como candidatas a fusión,
  y se pueden fusionar en un paso.
- CA-1.3 Fusionar es reversible y no pierde datos.
- CA-1.4 Mientras haya identidades sin confirmar, toda pantalla de estadísticas muestra el aviso
  de datos provisionales.
- CA-1.5 Una identidad marcada como bot se excluye de todos los agregados.

### UC-2 — Ver el panorama del equipo en un período

**Actor**: quien usa la app.
**Precondición**: UC-1 confirmado.

**Flujo**:
1. Elige período (últimos 30 / 90 días, trimestre, o rango) y repositorios.
2. El sistema muestra una tabla: una fila por persona, columnas M1, M2 (desglosado), M3a, M4
   (mediana), M6.
3. Puede ordenar por cualquier columna.
4. Cada columna tiene su definición accesible en un click.

**Criterios de aceptación**:
- CA-2.1 La tabla dice qué porcentaje de los PRs del período fue revisado por la app, y las
  columnas de cobertura parcial se marcan como tales.
- CA-2.2 Ordenar no cambia el color ni destaca al primero: no es un podio.
- CA-2.3 La advertencia de §2 está visible sin scrollear.
- CA-2.4 Una persona sin actividad en el período aparece con ceros, no desaparece.

### UC-3 — Quién abre más pull requests

**Flujo**: UC-2 ordenado por M1, con el desglose por repositorio y por semana.

**Criterios de aceptación**:
- CA-3.1 Se puede ver la evolución semanal, no sólo el total: alguien que abrió 20 PRs en una
  semana y nada en once es distinto de quien abrió dos por semana.
- CA-3.2 Los PRs de bots están excluidos y se dice cuántos se excluyeron.

### UC-4 — Dónde se concentra el retrabajo

**Flujo**:
1. Ordena por M3a o M3b.
2. Al elegir una persona, ve la lista de PRs que aportaron a ese número, cada uno con cuántos
   commits de corrección tuvo y qué hallazgos no se resolvieron a la primera.

**Criterios de aceptación**:
- CA-4.1 Todo número de retrabajo es navegable hasta los PRs concretos que lo componen. Un número
  agregado que no se puede auditar no sirve para conversar.
- CA-4.2 Se muestra junto a M5 (gravedad) del mismo período: retrabajo alto sobre hallazgos menores
  significa otra cosa que sobre bloqueantes.
- CA-4.3 La pantalla dice explícitamente que retrabajo alto es una señal para preguntar, no una
  conclusión.

### UC-5 — Volumen de cambio

**Flujo**: UC-2 ordenado por M2, con desglose agregado/borrado/neto y por tipo de archivo.

**Criterios de aceptación**:
- CA-5.1 Nunca se muestra un número único de "líneas": siempre los tres.
- CA-5.2 Se lista qué patrones se excluyeron por generados y cuántas líneas representaron.
- CA-5.3 Un commit de formateo masivo se puede marcar como excluido, y queda registrado.

### UC-6 — Ficha individual

**Flujo**:
1. Elige una persona.
2. Ve: sus métricas del período, su evolución semanal, sus PRs con estado, los hallazgos que
   recibió por gravedad, sus tiempos de ciclo, y su participación revisando a otros.

**Criterios de aceptación**:
- CA-6.1 Todo número enlaza al detalle que lo compone.
- CA-6.2 La ficha muestra M6 con el mismo peso visual que M1 y M2.
- CA-6.3 Se puede exportar como texto para pegarlo en una conversación —una uno-a-uno se prepara
  con datos, no con capturas de pantalla.

### UC-7 — Recolectar el histórico completo (D4)

**Actor**: el sistema, a pedido.
**Precondición**: repositorio conectado con clon local.

El módulo tiene que poder mostrar **todo el historial**, no una ventana móvil. Eso obliga a
recolectar una vez y después mantener incrementalmente.

**Flujo**:
1. La primera vez que se abre el módulo para un repositorio, el sistema dice cuánto hay que traer
   —cantidad de PRs históricos y de commits— y pide confirmación.
2. Actualiza el clon (`git fetch` de la rama principal, D6).
3. Recorre `git log` completo y guarda una fila por commit en `commit_stat`.
4. Trae los PRs históricos paginando, con el mecanismo que ya existe, y guarda `pr_stat`.
5. Las veces siguientes sólo procesa lo nuevo: commits posteriores al último `sha` registrado y
   PRs actualizados después de la última corrida.

**Criterios de aceptación**:
- CA-7.1 No se descarga histórico sin confirmación explícita la primera vez: son cientos de PRs por
  repositorio y miles de commits.
- CA-7.2 Si falla a mitad, lo ya traído queda guardado y se retoma desde ahí, sin volver a empezar.
- CA-7.3 El recorrido de git es de sólo lectura sobre el clon; el único cambio es el `fetch`.
- CA-7.4 Se registra hasta qué commit y hasta qué fecha se procesó cada repositorio.
- CA-7.5 Un `git log` de años no puede bloquear la interfaz: corre fuera del hilo de UI y con
  progreso visible.
- CA-7.6 Una reescritura de historia —rebase o force-push— se detecta porque el `sha` registrado ya
  no existe en la rama; en ese caso se recalcula ese repositorio desde cero y se avisa.

### UC-8 — Entender qué mide cada número

**Flujo**: desde cualquier columna o tarjeta, un click abre la definición: fórmula, familia de
cobertura, qué la distorsiona y qué no se puede concluir.

**Criterios de aceptación**:
- CA-8.1 Cada métrica de §5 tiene su texto, en los dos idiomas.
- CA-8.2 El texto incluye las distorsiones, no sólo la definición.

---

## 7. Modelo de datos

Migraciones nuevas (forward-only, siguiendo la regla del proyecto):

```
person            id, display_name, is_bot, created_at
person_identity   person_id, kind (GIT_EMAIL|GIT_NAME|FORGE_USER), value, confirmed, UNIQUE(kind,value)
commit_stat       repo_id, sha, person_id, authored_at, files, added, deleted, generated_added,
                  generated_deleted, UNIQUE(repo_id, sha)
pr_stat           repo_id, pr_id, person_id, created_on, merged_at, state, commits_before_review,
                  commits_after_review, UNIQUE(repo_id, pr_id)
stats_run         repo_id, period_from, period_to, ran_at, head_sha
```

Los agregados por persona **no se guardan**: se calculan por consulta. Guardar totales obliga a
recalcularlos cuando se fusiona una identidad, y esa desincronización es exactamente el tipo de
error que hace que nadie vuelva a confiar en el número.

`commit_stat` sí se guarda porque recorrer el git log de meses en cada apertura sería lento.

## 8. Interfaz

- Nueva sección en la navegación principal, al lado de Panel.
- Vista de equipo (UC-2) y ficha individual (UC-6).
- Advertencia de §2 como nota en el encabezado (D1: uso personal).
- Los números de cobertura parcial llevan una marca visual distinta de los de cobertura total.
- Las columnas de volumen distinguen lo que pasó por PR de lo que se empujó directo (D2).

### Selector de período (D4)

No hay un período por defecto fijo. Hay presets y rango libre:

| Preset | Qué muestra |
|---|---|
| **Todo** | Desde el primer commit registrado. Requiere UC-7 completo |
| **Últimos 3 meses** | Ventana móvil de 90 días |
| **Trimestre en curso** | Del 1° del trimestre a hoy |
| **Trimestre anterior** | El trimestre cerrado inmediatamente previo |
| **Rango** | Dos fechas elegidas a mano |

La elección se guarda y sobrevive al reinicio.

### Corte trimestral

Además del total del período elegido, la vista de equipo y la ficha individual muestran la
**evolución por trimestre**: una columna o una barra por trimestre, con la misma métrica.

Sirve para lo que un total no puede: ver si alguien viene subiendo o bajando, y comparar un
trimestre contra el mismo del año anterior. Un número acumulado de dos años no describe a nadie.

El trimestre se calcula por año calendario (Q1 = enero-marzo). Un trimestre incompleto —el actual—
se marca como tal, para que no se lea como caída.

## 9. Fases

1. ~~**Identidad** (UC-1) y recolección de `commit_stat` desde git.~~ **Hecha en v39.0.0.**
   Medido sobre los 25 pares reales de (nombre, email) de los siete repositorios: la resolución
   automática deja **17 personas**, y las 4 propuestas de fusión —Viktor, Lautaro, Tobías, Juan—
   son todas correctas, sin ninguna falsa. Aceptándolas quedan 13; la única irreducible es el
   apodo "Mapcky", que no tiene ninguna señal que lo delate.

   Dos cosas que la spec no había previsto y aparecieron al implementar:
   - **El orden importaba.** Una sola pasada dejaba a "Lautaro" partido en dos según qué commit
     apareciera primero, y en la base el `UNIQUE(kind,value)` descartaba el alias en silencio en
     vez de unir. Se agregó convergencia en los dos caminos: encontrar una identidad ya tomada no
     es un conflicto, es la prueba de que las dos personas son la misma.
   - **Los tests se contaminaban entre sí**: `person` es global, no por repositorio, así que
     borrar el repo al terminar no la limpia. Cada test va con su propia base.
2. **M2 y vista de equipo** (UC-2, UC-5) — **hecha en v40.0.0**: período con presets y evolución
   trimestral, volumen siempre desglosado, generadas en columna propia, sin podio, advertencia
   visible, bots fuera.

   **M1 y M4 quedaron afuera a propósito.** La spec las ponía en esta fase asumiendo que los PRs
   se podían derivar; medido, no alcanza: git ve **9 merges de PR en 12 meses** en `kubrik-erp-be`
   —donde la app conoce PRs hasta el #152—, **53** en `kubrik-erp-fe` y **0** en `talos-apirest`.
   La cobertura depende de si el equipo mergea con merge commit o con squash, y de qué ramas tenga
   el clon. Calcular "PRs abiertos" o "tiempo de ciclo" sobre eso daría números que parecen reales
   y subcuentan feo, que es justo lo que §2 dice que no hay que hacer. Se mueven a la fase del
   histórico del proveedor (UC-7).
3. **M3b, M5, M6** — **hecha en v41.0.0**: participación revisando (M6) primero, hallazgos
   recibidos por gravedad normalizados por PR (M5) y los no resueltos a la primera (M3b).

   Apareció un agujero que la spec no había previsto: **la review no guardaba de quién era el
   PR.** `pr_cache` sólo tiene los abiertos, así que de 12 PRs revisados apenas 7 tenían autor, y
   205 de 264 comentarios estaban en PRs de autor desconocido. Se agregó `review.pr_author`
   (migración v38), se rellenó desde `pr_cache` lo que se pudo —20 de 90 reviews— y de ahora en
   más se guarda al arrancar cada corrida. Lo viejo no se recupera sin el histórico del proveedor,
   así que la pantalla declara sobre cuántos PRs calcula cada número.

   **M3a (commits de corrección) y M3c (vueltas de conversación) quedan pendientes**: la primera
   necesita cruzar commits con el sha revisado por PR, y la segunda depende de la misma
   atribución de autoría que hoy cubre poco.
5. **Histórico bajo demanda** (UC-7) — **hecho en v42.0.0** para los pull requests: botón
   explícito, un estado por vez para que un 401 cueste un estado y no la corrida, y relleno de
   `review.pr_author` con lo que trae. Con eso quedan **M1** (PRs abiertos) y **M4** (tiempo de
   ciclo, mediana y percentil 90), que la fase 2 había tenido que dejar afuera.

   Falta la parte de git de UC-7: hoy la recolección de commits trae los últimos 12 meses y no
   todo el historial. Y `stats_run` guarda hasta dónde se procesó pero todavía no se usa para
   recolectar sólo lo nuevo (CA-7.4/CA-7.6).

4. **Ficha individual** (UC-6) y exportación — pendiente.

Cada fase es usable por sí sola. La 1 no muestra estadísticas y aun así hay que hacerla primero.

## 10. Preguntas abiertas

Las seis originales quedaron resueltas en §0. Lo que queda por decidir aparece cuando la fase 1
esté andando y se vean personas y números reales:

1. **Umbral de "identidad candidata a fusión"**: hoy la heurística compara nombre normalizado. Si
   con datos reales genera falsos positivos, habrá que endurecerla o pedir confirmación siempre.
2. **Qué hacer con quien ya no está en el equipo**: ¿se archiva la persona y sale de los agregados
   por defecto, o sigue apareciendo en los períodos en que sí trabajó? Lo segundo es más honesto
   con el histórico; lo primero es más cómodo de leer.
3. **Repositorios sin clon local**: hoy todos lo tienen. Si aparece uno sin clon, las métricas de
   git no se pueden calcular y habría que mostrarlo con cobertura reducida en vez de vacío.
