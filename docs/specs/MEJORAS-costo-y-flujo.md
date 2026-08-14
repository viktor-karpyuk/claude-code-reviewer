# MEJORAS — Costo y flujo de reviews

**Fecha:** 2026-08-13 · **Estado:** analizado, pendiente de ejecución
**Base de evidencia:** la base local (`~/Library/Application Support/AICodeReviewer/acr.db`) con
89 reviews, 196 hallazgos, 7 repos y **US$ 374,83 de consumo acumulado**. Todos los números de
este documento salen de ahí, no de intuición.

---

## 1. Diagnóstico

### 1.1 Un solo patrón explica el 60% del gasto

| PR | Reviews | Costo | SHAs distintos |
|---|---|---|---|
| kubrik-erp-be #149 (pos-ar-fiscal) | 25 | **US$ 225,32** | 23 |
| kubrik-erp-be #151 (KS-600) | 17 | US$ 47,02 | 12 |
| kubrik-erp-fe #19 (pos-ar-fiscal) | 14 | US$ 59,19 | 13 |
| kubrik-erp-fe #127 (KS-600) | 12 | US$ 13,66 | 8 |

Un PR largo se revisa **decenas de veces** a medida que llegan commits. Eso es legítimo — el
código nuevo hay que mirarlo. Lo que no es legítimo es *cómo*: `ReviewEngine.kt` arma siempre el
rango completo `origin/target...origin/source`, así que la pasada 25 del #149 releyó y re-razonó
la rama entera, incluyendo los archivos que ya habían sido aprobados 24 veces.

Costo promedio de una review completa: **US$ 5,51** y ~7 minutos de reloj (mediana 431 s).

### 1.2 Re-reviews del mismo commit: US$ 10,35 tirados (no 22)

21 de las 89 reviews corrieron sobre un `head_sha` ya revisado, sin un solo commit en el medio.
**Las 21 fueron manuales**: el modo automático ya salta lo que vio (`AutoReviewer.kt:62`, vía
`existsForHead`), así que ese frente está cubierto desde antes.

Pero al separar por el estado de la review previa, la mitad no es desperdicio:

| Caso | Reviews | Costo |
|---|---|---|
| La previa había terminado **DONE** → repetir no aporta nada | 11 | **US$ 10,35** |
| La previa **falló o se canceló** → reintento legítimo | 10 | US$ 11,75 |

Avisar en el segundo caso sería estorbar: cuando una review se cae (y 15 de 18 fallos fueron
porque se cerró la app), volver a correr el mismo sha es exactamente lo correcto.

El desperdicio real, entonces, son **11 reviews y US$ 10,35** — y el detalle temporal muestra de
qué se trata: los repes ocurren **57 h, 79 h, 127 h, 139 h después** de la review original. No es
alguien re-corriendo a propósito; es alguien que volvió al PR días más tarde y no se acordaba de
que ya lo había mirado. Por eso el valor de la mejora no es sólo la plata: son también los ~7
minutos de espera (mediana 431 s) para redescubrir un resultado que ya estaba guardado.

### 1.3 Los "87 hallazgos sin destino" no son un problema de triage

De los 87 hallazgos ni publicados ni descartados, **81 pertenecen a reviews superadas** (hubo
una review más nueva del mismo PR). La pantalla ya los oculta — `forReview` acota a la review
vigente, decisión documentada en `Repositories.kt` — así que son filas muertas, no una cola de
trabajo. Sólo 6 son de la review vigente de su PR.

Conclusión: **no hace falta una feature de triage/descarte en lote.** El problema es que cada
re-review completa *regenera* los hallazgos en vez de arrastrarlos, y eso lo resuelve la mejora
№1. Dato adicional: sólo 2 de los 87 son duplicados exactos (mismo título + archivo) — Claude
reformula los títulos entre corridas, así que cualquier dedupe tiene que hacerlo el modelo
comparando contra la lista anterior, no un `GROUP BY`.

### 1.4 El módulo de guías se shipeó vacío

La 34.0.0 agregó carga de convenciones por repo y globales. Documentos cargados a hoy: **0**.
Mientras tanto, el prompt le pide a Claude que *encuentre* los `CLAUDE.md` del repo por su cuenta
(`ReviewPrompt.kt:285`) — funciona, pero gasta turnos de exploración en cada review y depende de
que los encuentre.

### 1.5 Fallos: la causa №1 ya tiene arreglo, falta verificarlo

18 reviews FAILED; **15 son "la app se cerró mientras corría"** — exactamente lo que la 33.0.0
resuelve con `pending_job`. Aún no se ejercitó en la vida real (0 jobs pendientes hoy). Las otras:
2 "Stream closed", 1 credencial de git deprecada (app passwords de Bitbucket). Nada que codear;
observar la próxima interrupción real.

---

## 2. Mejoras, re-analizadas

### M1 — Review incremental *(la que paga sola)*

**Qué:** cuando un PR ya tiene una review DONE y llegaron commits nuevos, revisar sólo
`últimoShaRevisado..head` y **arrastrar** los hallazgos previos: el prompt incluye la lista
anterior y Claude dictamina, por cada uno, si sigue vivo, fue corregido o quedó obsoleto por el
diff nuevo.

**Por qué funciona acá:** la infraestructura ya existe a medias — `VerificationNeed.kt` ya
calcula `sinceSha`, y el prompt de seguimiento ya opera con un `rangeSinceReview`. Es extender
ese patrón a la review principal.

**Efecto esperado:** en un PR tipo #149, 23 lecturas completas se vuelven 1 completa + 22 diffs
chicos. Con que la pasada incremental cueste un tercio de la completa (esperable: el diff entre
dos pushes es una fracción de la rama), el histórico habría costado ~US$ 90 en vez de US$ 225.
Bonus: deja de fabricar filas muertas (§1.3), porque los hallazgos vigentes se arrastran en vez
de regenerarse con otro `review_id`.

**Riesgos / decisiones:**
- **Deriva:** N incrementales encadenadas pueden acumular puntos ciegos (interacciones entre el
  diff viejo y el nuevo que ninguna pasada vio junta). Mitigación: la *pasada final* ya existente
  sigue siendo completa, y un botón "review completa" fuerza el comportamiento de hoy.
- Si el PR cambió de destino o hubo rebase (el `sinceSha` ya no es ancestro de `head`), caer a
  review completa automáticamente — detectable con `git merge-base --is-ancestor`.
- Los hallazgos arrastrados conservan su `published_id`/estado; los nuevos nacen normales. El
  número de línea de un arrastrado puede haber corrido: Claude lo re-ancla contra el diff actual.

### M2 — Freno a la re-review del mismo commit *(barata, corta un goteo)*

**Qué:** al disparar manualmente una review sobre un `head_sha` cuya review previa terminó
**DONE**, avisar antes de correr: "Este commit ya lo revisaste el <fecha> — N hallazgos,
US$ X. ¿Correr igual?", con la opción de ver el resultado guardado. No bloquear: re-correr con
otra profundidad o con guías nuevas es un uso legítimo (D3).

**Alcance ajustado tras §1.2:**
- El auto-reviewer **ya** salta shas vistos; no se toca.
- **Sólo** se avisa si la previa fue DONE. Si falló o se canceló, se corre sin preguntar: ahí
  repetir es lo correcto y preguntar sería estorbar.

**Evidencia:** 11 reviews, US$ 10,35, con repes a 57–139 h de distancia. La plata es poca; lo que
más pesa es no esperar ~7 minutos para redescubrir un resultado ya guardado.

### M3 — Importar `CLAUDE.md` del repo como guía *(llena la feature vacía con un click)*

**Qué:** botón "Importar CLAUDE.md del repositorio" en el formulario del repo: busca
`CLAUDE.md` en la raíz del clon local (y opcionalmente los anidados), lo carga como guía del
repo con `source` = ruta del archivo. Al estar como guía, entra directo al prompt (tope
`MAX_GUIDELINES_CHARS = 60.000` ya implementado) y Claude deja de gastar turnos buscándolo.

**Decisión incluida:** marcar la guía importada como *vinculada al archivo* y re-leerla al
disparar cada review (si la ruta sigue existiendo), para que no quede una copia congelada de un
documento que vive en git y cambia. Si el archivo desapareció, se usa la última copia y se avisa.

### M4 — ~~Triage de hallazgos sin destino~~ *(descartada)*

Descartada por el diagnóstico §1.3: 81/87 son residuo de re-reviews completas y la UI ya los
oculta. M1 corta la generación. Único remanente opcional: una limpieza que marque como obsoletos
los hallazgos de reviews superadas nunca publicados — cosmética de base, cero UI. Se decide al
implementar M1.

### M5 — Estadísticas por persona *(ya especificada, espera turno)*

Spec completa en `STATS-estadisticas-por-persona.md` (decisiones D1–D6 cerradas, 8 casos de
uso). Arranca por resolución de identidad. Es la feature nueva más grande del backlog; no
compite con M1–M3, que son de eficiencia.

### M6 — Verificación del resume de jobs *(observar, no codear)*

La próxima vez que la app se cierre con una review corriendo, comprobar que al reabrir la
retoma. Si no la retoma, eso pasa al frente de la cola como bug.

---

## 3. Plan de ejecución

Orden elegido: primero los dos arreglos chicos que cortan gasto hoy (M2, M3), después el grande
(M1) con el terreno ya limpio — M2 además instala el concepto "¿qué review previa tiene este
PR?" que M1 reutiliza. STATS al final por tamaño.

Regla de versionado del proyecto: requerimiento nuevo ⇒ **major**.

### Fase 1 — v36.0.0 · M2: freno al mismo sha
1. `ReviewRepository`: consulta "última review **DONE** para (repo, pr, head_sha)", con su fecha,
   costo y cantidad de hallazgos.
2. Diálogo de confirmación en el disparo manual: fecha, hallazgos y costo de la previa; botones
   correr igual / cancelar.
3. ~~Auto-reviewer: skip~~ — ya implementado (`existsForHead`), no se toca.
4. Tests: que la consulta ignore reviews FAILED/CANCELLED (si no, el aviso aparecería justo
   cuando reintentar es lo correcto). i18n ES+EN del diálogo.

### Fase 2 — v37.0.0 · M3: importar CLAUDE.md
1. Botón en `RepoFormPanel` (sección de guías del repo): detectar `CLAUDE.md` en el clon,
   mostrar tamaño, importar.
2. `GuidelineRepository`: marca de "vinculada a archivo" (nueva columna, migración v35 del
   esquema) + re-lectura al armar el prompt cuando la ruta existe.
3. Tests: import, re-lectura con archivo cambiado, archivo desaparecido. i18n.

### Fase 3 — v38.0.0 · M1: review incremental
1. **Detección:** al disparar sobre un PR con review DONE previa, calcular `sinceSha..head`;
   validar con `merge-base --is-ancestor`; si no es ancestro (rebase) → completa.
2. **Prompt incremental** en `ReviewPrompt`: rango corto + lista de hallazgos vigentes previos
   (título, archivo, línea, estado) + instrucción de dictaminar cada uno: `SIGUE_VIVO` (con
   línea re-anclada) / `CORREGIDO` / `OBSOLETO`, más hallazgos nuevos sólo del diff corto.
3. **Persistencia:** los arrastrados actualizan su fila (misma identidad, mismo
   `published_id`); los `CORREGIDO`/`OBSOLETO` se cierran con motivo; los nuevos se insertan.
   La review nueva referencia a la anterior (columna `previous_review_id`, migración).
4. **Escotillas:** botón "review completa" que ignora el incremental; la pasada final sigue
   siendo completa siempre.
5. **UI:** la pantalla del PR indica "incremental desde <sha corto>" y ofrece la completa.
6. Tests: detección de ancestro, mapeo de dictámenes a estados, arrastre de `published_id`,
   caída a completa tras rebase. i18n.
7. **Medición de éxito:** tras dos semanas, comparar costo promedio por pasada incremental vs
   completa en la base. Si no baja de la mitad, revisar el prompt.

### Fase 4 — v39.0.0+ · M5: STATS
Según su propia spec, empezando por resolución de identidad. Se re-planifica al llegar.

### Transversal
- M6 (resume de jobs): sólo observación; se registra el resultado acá cuando ocurra.
- El backlog 23.0.0→35.0.0 sigue sin commitear/pushear — conviene cerrar ese ciclo de
  commit+tag+release antes de la Fase 1, para que cada fase quede en un commit propio.

## Registro de decisiones

| # | Decisión | Motivo |
|---|---|---|
| D1 | M4 descartada | 81/87 hallazgos "pendientes" son residuo; la UI ya los oculta |
| D2 | Dedupe de hallazgos lo hace el modelo, no SQL | sólo 2/87 duplicados exactos; Claude reformula títulos |
| D3 | M2 avisa, no bloquea | re-correr con otra profundidad/guías es un uso legítimo |
| D4 | Guía importada de CLAUDE.md se re-lee del archivo | el documento vive en git; una copia congelada miente |
| D5 | Pasada final nunca es incremental | es el contrapeso de la deriva de N incrementales |
