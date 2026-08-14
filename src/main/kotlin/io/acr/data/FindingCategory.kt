package io.acr.data

/**
 * Qué clase de problema es un hallazgo.
 *
 * Es un eje distinto de la gravedad y hacían falta los dos. La gravedad dice cuán urgente;
 * la categoría dice qué clase de cosa es, y eso cambia quién lo atiende y cuándo. Para el que
 * recibe el comentario no es lo mismo "esto rompe la lógica de negocio" que "esto convendría
 * resolverlo con otro patrón", aunque los dos lleguen marcados como importantes: lo primero se
 * arregla antes de mergear y lo segundo se conversa.
 *
 * Son cuatro y no más. Cada categoría de más es una decisión que el modelo puede errar, y la
 * utilidad de la etiqueta cae apenas hay que pensar en cuál de dos parecidas va.
 *
 * El orden de declaración es el de precedencia cuando un hallazgo entra en varias: algo que rompe
 * el negocio se reporta como funcional aunque además sea un problema de diseño, porque es lo que
 * define qué hacer con él.
 */
enum class FindingCategory(val labelKey: String) {
    /** Rompe o cambia el comportamiento que el negocio espera. Se arregla antes de mergear. */
    FUNCTIONAL("cat.functional"),

    /** Defecto de código: se rompe con cierta entrada o en cierto estado. */
    BUG("cat.bug"),

    /** Patrón, arquitectura, acoplamiento, capa equivocada. Es una conversación, no una urgencia. */
    DESIGN("cat.design"),

    /** Viola una regla escrita en las convenciones que cargó el equipo. */
    CONVENTION("cat.convention"),
    ;

    companion object {
        /** Lo que devolvió el modelo, o null si no se entiende. Inventar una categoría sería peor. */
        fun fromApi(raw: String?): FindingCategory? =
            raw?.trim()?.uppercase()?.let { v -> entries.firstOrNull { it.name == v } }
    }
}
