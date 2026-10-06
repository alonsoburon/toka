package com.toka.app.data

import com.toka.app.data.model.TemplateDTO

/** Plantillas que cuelgan, directa o indirectamente, de [rootId] en un flujo (sin incluir a [rootId]). */
fun descendantIds(rootId: String, templates: List<TemplateDTO>): Set<String> {
    val found = linkedSetOf<String>()
    val pending = ArrayDeque(listOf(rootId))
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        templates.filter { it.triggerTemplateId == current && it.id != rootId && found.add(it.id) }
            .forEach { pending.addLast(it.id) }
    }
    return found
}
