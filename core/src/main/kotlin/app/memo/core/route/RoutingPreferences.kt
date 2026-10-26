package app.memo.core.route

import org.json.JSONObject

/** The step roles the router places. A role is what a step needs, not which provider does it. */
enum class StepRole { PLAN, RESEARCH, DRAFT, CAPTURE, CRITIQUE, SYNTHESIZE, IMAGE, CHAT }

/** Where a target runs. Maps to [app.memo.core.policy.ChannelSwitches] channel keys by lowercase name. */
enum class TargetChannel { LOCAL, WEB, APP, API }

/**
 * How a role chooses between local and frontier targets.
 * - LOCAL_FIRST: local targets first, then frontier targets as fallback.
 * - FRONTIER_FIRST: frontier (WEB, APP, API) first, then local as fallback.
 * - LOCAL_ONLY / FRONTIER_ONLY: never leave that side.
 */
enum class RoutingMode { LOCAL_FIRST, FRONTIER_FIRST, LOCAL_ONLY, FRONTIER_ONLY }

/**
 * User routing choices: one [RoutingMode] per role plus an optional pinned target id per role.
 * Immutable; the `with*` helpers return copies. Defaults follow the build plan's section 2g
 * routing per task class. A pin overrides the mode, but only while the pinned target is eligible.
 */
data class RoutingPreferences(
    val modes: Map<StepRole, RoutingMode> = DEFAULT_MODES,
    val pins: Map<StepRole, String> = emptyMap(),
) {
    init {
        require(pins.values.all { it.isNotBlank() }) { "pinned target ids must not be blank" }
    }

    fun modeFor(role: StepRole): RoutingMode = modes[role] ?: DEFAULT_MODES.getValue(role)

    fun pinFor(role: StepRole): String? = pins[role]

    fun withMode(role: StepRole, mode: RoutingMode): RoutingPreferences =
        copy(modes = modes + (role to mode))

    /** Pass null to clear the pin for [role]. */
    fun withPin(role: StepRole, targetId: String?): RoutingPreferences =
        copy(pins = if (targetId == null) pins - role else pins + (role to targetId))

    fun toJson(): JSONObject {
        val modeObj = JSONObject()
        for (role in StepRole.entries) modeObj.put(role.name, modeFor(role).name)
        val pinObj = JSONObject()
        for ((role, id) in pins) pinObj.put(role.name, id)
        return JSONObject().put("modes", modeObj).put("pins", pinObj)
    }

    companion object {
        val DEFAULT_MODES: Map<StepRole, RoutingMode> = mapOf(
            StepRole.PLAN to RoutingMode.LOCAL_FIRST,
            StepRole.CHAT to RoutingMode.LOCAL_FIRST,
            StepRole.CAPTURE to RoutingMode.LOCAL_FIRST,
            StepRole.RESEARCH to RoutingMode.FRONTIER_FIRST,
            StepRole.CRITIQUE to RoutingMode.FRONTIER_FIRST,
            StepRole.DRAFT to RoutingMode.LOCAL_FIRST,
            StepRole.SYNTHESIZE to RoutingMode.LOCAL_FIRST,
            StepRole.IMAGE to RoutingMode.LOCAL_FIRST,
        )

        /** Tolerant reader: unknown roles and modes are dropped, missing ones take the default. */
        fun fromJson(raw: JSONObject): RoutingPreferences {
            val modes = LinkedHashMap<StepRole, RoutingMode>()
            val modeObj = raw.optJSONObject("modes")
            if (modeObj != null) {
                for (role in StepRole.entries) {
                    val name = modeObj.optString(role.name, "")
                    val mode = RoutingMode.entries.firstOrNull { it.name == name }
                    if (mode != null) modes[role] = mode
                }
            }
            val pins = LinkedHashMap<StepRole, String>()
            val pinObj = raw.optJSONObject("pins")
            if (pinObj != null) {
                for (role in StepRole.entries) {
                    val id = pinObj.optString(role.name, "")
                    if (id.isNotBlank()) pins[role] = id
                }
            }
            return RoutingPreferences(modes = DEFAULT_MODES + modes, pins = pins)
        }
    }
}
