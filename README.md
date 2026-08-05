# AI Code Reviewer

App de escritorio (Kotlin + Compose Desktop) para revisar pull requests con Claude Code.
Mismo stack y convenciones que Mongo Explorer v3.

```bash
./gradlew run            # arrancar
./gradlew packageDmg     # empaquetar .dmg
./gradlew test           # tests (los que pegan a la red se saltean sin ACR_TEST_TOKEN)
```

## Qué hace

1. **Conectar repositorios** — Bitbucket Cloud y GitHub. Cada repo guarda sus coordenadas
   remotas, la ruta del clon local y su token, cifrado con AES-256-GCM.
2. **Levantar los PRs** — al elegir un repo trae solos los pull requests abiertos.
3. **Revisar** — corre la consola local de Claude Code como subproceso dentro del clon, con
   el progreso en vivo (qué archivo lee, qué comando git corre) y botón de cancelar.
4. **Publicar** — muestra la review en Markdown, editable, y la publica como comentario del PR.

## Por qué la consola y no la API

Es la decisión de diseño central y la razón de que la app no pida ninguna API key.

- `claude -p` corre headless y autentica con la **sesión de Claude Code** del usuario. No hay
  `ANTHROPIC_API_KEY` en ningún lado: ni en el código, ni en la base, ni en el entorno.
- `--output-format stream-json --verbose` emite eventos NDJSON, que es lo que alimenta el
  feed de progreso y de donde salen `session_id` y `total_cost_usd`.
- El Agent SDK era la alternativa, y queda descartada por dos motivos independientes:
  autentica **sólo** con API key (no sirve una suscripción) y no tiene binding JVM.

Verificado en esta máquina con Claude Code 2.1.221.

## Seguridad del subproceso

Las reviews corren con `--permission-mode dontAsk` y lista blanca:

- **Permitido**: `Read`, `Grep`, `Glob`, y git de solo lectura (`git diff|log|show|blame|ls-tree|rev-parse`).
- **Denegado**: `Edit`, `Write`, `WebFetch`, `WebSearch`.

Una review no puede modificar el working copy ni salir a la red. `dontAsk` deniega lo que no
esté en la lista en vez de quedarse esperando una confirmación que nadie puede responder en
un run headless. `permission_denials` del resultado queda registrado.

## Dónde guarda las cosas

| Qué | Dónde |
|---|---|
| Base SQLite (repos, reviews, prefs) | `~/Library/Application Support/AICodeReviewer/acr.db` |
| Clave maestra AES | `~/.acr/master.key` (permisos `600`) |
| Transcripts de las sesiones | `~/.claude/projects/<hash-del-repo>/` (los escribe Claude Code) |

La clave vive fuera de la base a propósito: copiar el `.db` solo no alcanza para leer los tokens.

## Notas de operación

- El repo local tiene que ser un clon git con remote `origin`. La app hace `git fetch` de las
  dos ramas antes de cada review; sin eso el rango del diff apunta a refs viejas y la review
  describiría una versión anterior del PR.
- Los tokens de Atlassian recién creados devuelven 401 de forma intermitente mientras propagan.
  El cliente HTTP reintenta 401 y 429; un 403/404 no se reintenta porque es una respuesta real.
- Bitbucket quiere el token como **Bearer**. El Basic `email:token` que documenta Atlassian
  devuelve 401 contra `api.bitbucket.org`.

## Licencia

[Apache License 2.0](LICENSE).

La licencia cubre el código de este repositorio. La app no incluye ni provee acceso a Claude:
corre el CLI de Claude Code de la máquina y autentica con la sesión del usuario, así que cada
quien usa su propio acceso, sujeto a los términos de Anthropic.
