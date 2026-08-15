package com.maxlass.studio.pack.format.model

/**
 * A transition from a stage node to one option of an [ActionNode].
 *
 * @property actionNode Target action node (resolved later by readers when nodes are linked).
 * @property optionIndex Index of the selected option inside [ActionNode.options].
 */
data class Transition(
    var actionNode: ActionNode?,
    var optionIndex: Short,
)

/**
 * Which controls are enabled on a stage node.
 */
data class ControlSettings(
    var wheelEnabled: Boolean,
    var okEnabled: Boolean,
    var homeEnabled: Boolean,
    var pauseEnabled: Boolean,
    var autoJumpEnabled: Boolean,
)
