package app.memo.runtime

import app.memo.core.prompts.ContextPacket
import app.memo.core.registry.Workspace

/**
 * Builds the bounded context packet the engine hands to executors. Only counts
 * and a few recent titles leave the registry; the packet stays within
 * [ContextPacket] caps. Runs on the runtime thread, where the workspace is owned.
 */
object PacketBuilder {

    const val LATEST_TASKS = 5
    const val LATEST_PROJECTS = 3

    fun build(workspace: Workspace): ContextPacket {
        val latestTasks = workspace.tasks.items()
            .sortedByDescending { it.updatedAt }
            .take(LATEST_TASKS)
            .map { it.title }
        val latestProjects = workspace.projects.items()
            .sortedByDescending { it.updatedAt }
            .take(LATEST_PROJECTS)
            .map { it.name }
        return ContextPacket.Builder()
            .put("people_count", workspace.people.size.toString())
            .put("task_count", workspace.tasks.size.toString())
            .put("draft_count", workspace.drafts.size.toString())
            .put("latest_tasks", latestTasks.joinToString("; "))
            .put("latest_projects", latestProjects.joinToString("; "))
            .build()
    }
}
