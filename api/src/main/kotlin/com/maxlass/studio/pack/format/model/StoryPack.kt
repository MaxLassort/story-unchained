package com.maxlass.studio.pack.format.model

/**
 * Abstract node of a story pack.
 *
 * @property enriched Optional enrichment metadata (title/type/position) used by the Studio editor.
 */
abstract class Node(var enriched: EnrichedNodeMetadata?)

/**
 * A stage node: displays an image/audio and offers transitions (OK / HOME).
 */
class StageNode(
    var uuid: String?,
    var image: ImageAsset?,
    var audio: AudioAsset?,
    var okTransition: Transition?,
    var homeTransition: Transition?,
    var controlSettings: ControlSettings?,
    enriched: EnrichedNodeMetadata?,
) : Node(enriched)

/**
 * An action node: a choice point whose options are [StageNode]s.
 */
class ActionNode(
    var options: List<StageNode>?,
    enriched: EnrichedNodeMetadata?,
) : Node(enriched)

/**
 * In-memory representation of a story pack.
 *
 * @property uuid UUID of the pack (first stage node's UUID in archive/raw formats).
 * @property factoryDisabled Whether factory content is disabled.
 * @property version Pack format version.
 * @property stageNodes Ordered stage nodes (square-one first in archive/raw formats).
 * @property enriched Optional pack-level enrichment metadata.
 * @property nightModeAvailable Whether night mode is available.
 */
class StoryPack(
    var uuid: String?,
    var factoryDisabled: Boolean,
    var version: Short,
    var stageNodes: List<StageNode>?,
    var enriched: EnrichedPackMetadata?,
    var nightModeAvailable: Boolean,
)
