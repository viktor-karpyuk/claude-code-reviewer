package io.acr.jira

/**
 * La clave de un ticket, como `KS-654` o `POS-84`.
 *
 * Se exige mayúsculas a propósito. Con minúsculas, cualquier rama del estilo
 * `tasks-ms-rabbit-placeholders` o `feature/pos-ar-fiscal` daría un falso positivo y la app
 * mostraría un ticket que no existe, o peor: uno que existe y no tiene nada que ver.
 *
 * El proyecto va de dos a diez caracteres porque las claves de Jira son cortas por diseño; sin
 * tope, `A-1` dentro de una fecha o un hash también entraría.
 */
private val CLAVE = Regex("\\b([A-Z][A-Z0-9]{1,9})-(\\d{1,6})\\b")

/**
 * Los tickets que menciona un texto, en orden de aparición y sin repetir.
 *
 * Devuelve varios porque de verdad pasa: un PR puede cerrar dos tickets, y quedarse con el primero
 * escondería el otro.
 */
fun issueKeysIn(texto: String?): List<String> {
    if (texto.isNullOrBlank()) return emptyList()
    return CLAVE.findAll(texto).map { it.value }.distinct().toList()
}

/**
 * El ticket de un pull request, mirando primero la rama y después el título.
 *
 * La rama primero porque es la que se crea al empezar y casi nunca se toca; el título se edita a
 * mano y se equivoca. Se vio en estos repositorios: un PR con rama `KS-655` y título `KS-644
 * history`, dos tickets distintos. Con la rama primero gana el que de verdad se está trabajando.
 *
 * @return los tickets encontrados, los de la rama antes que los del título.
 */
fun issueKeysFor(branch: String?, title: String?): List<String> {
    val deRama = issueKeysIn(branch)
    val deTitulo = issueKeysIn(title)
    return (deRama + deTitulo).distinct()
}

/** Un ticket, con lo que hace falta para revisar el PR sabiendo qué se pidió. */
data class JiraIssue(
    val key: String,
    val summary: String,
    val description: String,
    val type: String,
    val status: String,
    val assignee: String?,
    val url: String,
)
