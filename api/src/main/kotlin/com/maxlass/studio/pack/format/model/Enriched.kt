package com.maxlass.studio.pack.format.model

/**
 * Enrichment metadata attached to a pack.
 */
class EnrichedPackMetadata(
    var title: String?,
    var description: String?,
)

/**
 * Enrichment metadata attached to a node.
 */
class EnrichedNodeMetadata(
    var name: String?,
    var type: EnrichedNodeType?,
    var groupId: String?,
    var position: EnrichedNodePosition?,
)

/**
 * 2D position of a node in the Studio editor.
 */
data class EnrichedNodePosition(
    var x: Short,
    var y: Short,
)

/**
 * Node type in the Studio editor (code on device, label in the JSON format).
 */
enum class EnrichedNodeType(val code: Byte, val label: String) {
    STAGE(1, "stage"),
    ACTION(2, "action"),
    COVER(17, "cover"),
    MENU_QUESTION_ACTION(33, "menu.questionaction"),
    MENU_QUESTION_STAGE(34, "menu.questionstage"),
    MENU_OPTIONS_ACTION(35, "menu.optionsaction"),
    MENU_OPTION_STAGE(36, "menu.optionstage"),
    STORY(49, "story"),
    STORY_ACTION(50, "story.storyaction"),
    ;

    companion object {
        /** Resolves a type from its device [code], or null when unknown. */
        fun fromCode(code: Byte): EnrichedNodeType? = entries.firstOrNull { it.code == code }

        /** Resolves a type from its JSON [label], or null when unknown. */
        fun fromLabel(label: String): EnrichedNodeType? = entries.firstOrNull { it.label == label }
    }
}
