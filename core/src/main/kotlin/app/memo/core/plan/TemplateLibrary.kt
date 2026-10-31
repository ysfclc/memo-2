package app.memo.core.plan

import app.memo.core.domain.WorkflowTemplate

/**
 * Seeded workflow templates for use cases UC-1..UC-5. They double as few-shot
 * examples for the orchestrator: each planJson is a realistic plan in the exact
 * contract [PlanProposalParser] accepts and [WorkflowPolicy] approves.
 */
object TemplateLibrary {

    private const val SEED_TIME = 0L

    val seeds: List<WorkflowTemplate> = listOf(
        WorkflowTemplate(
            name = "uc1_brainstorm",
            description = "Brainstorm an idea into an outline, consult a frontier model, and save next-step tasks after approval.",
            intentExamples = listOf(
                "brainstorm my app idea",
                "help me think through a new project",
                "turn this idea into next steps",
            ),
            planJson = """
                {"title":"Brainstorm a project and capture next steps",
                 "steps":[
                   {"id":"outline","kind":"reason","title":"Outline the idea",
                    "prompt":"Outline the idea in five short points: problem, audience, core feature, risks, open questions."},
                   {"id":"consult","kind":"browser_automation","title":"Consult a frontier model",
                    "provider":"chatgpt","artifactHint":"capture: critique of the outline and missing angles"},
                   {"id":"next_steps","kind":"reason","title":"Derive next steps",
                    "prompt":"Combine the outline and the consult notes into three concrete next-step tasks, each with an owner and a first action."},
                   {"id":"approve_tasks","kind":"human_approval","title":"Approve the next-step tasks","artifactType":"task"},
                   {"id":"save_tasks","kind":"data_persist","title":"Save the approved tasks","artifactType":"task"}
                 ]}
            """.trimIndent(),
            createdAt = SEED_TIME,
            updatedAt = SEED_TIME,
            version = 1,
        ),
        WorkflowTemplate(
            name = "uc2_crm_capture",
            description = "Capture a person from a research pass, fill their CRM record, and save it after approval.",
            intentExamples = listOf(
                "add this person to my crm",
                "research a contact and save them",
                "capture a new person record",
            ),
            planJson = """
                {"title":"Research a contact and capture the person record",
                 "steps":[
                   {"id":"research","kind":"browser_automation","title":"Research the contact",
                    "provider":"claude","artifactHint":"capture: public role, company and recent activity of the person"},
                   {"id":"fill_record","kind":"reason","title":"Fill the person record",
                    "prompt":"Fill the person record with full name, role, company and a one-line summary. Only use facts present in the research capture; leave unknown fields empty."},
                   {"id":"approve_person","kind":"human_approval","title":"Approve the person record","artifactType":"person"},
                   {"id":"save_person","kind":"data_persist","title":"Save the person","artifactType":"person"}
                 ]}
            """.trimIndent(),
            createdAt = SEED_TIME,
            updatedAt = SEED_TIME,
            version = 1,
        ),
        WorkflowTemplate(
            name = "uc3_job_apply",
            description = "Read a job posting, tailor an application, and submit it only after explicit approval.",
            intentExamples = listOf(
                "apply to this job",
                "find and apply to a role",
                "tailor my application for this posting",
            ),
            planJson = """
                {"title":"Tailor and submit a job application",
                 "steps":[
                   {"id":"read_posting","kind":"browser_automation","title":"Read the job posting",
                    "provider":"gemini","artifactHint":"capture: job title, requirements and application form fields"},
                   {"id":"tailor","kind":"reason","title":"Tailor the application",
                    "prompt":"Tailor the application to the posting requirements using only the facts in the capture and the user's profile. Keep it under 250 words."},
                   {"id":"approve_application","kind":"human_approval","title":"Approve the application","artifactType":"application"},
                   {"id":"submit_application","kind":"browser_automation","title":"Submit the application",
                    "provider":"gemini","artifactHint":"send: application","requiresGate":true},
                   {"id":"save_application","kind":"data_persist","title":"Save the application record","artifactType":"application"}
                 ]}
            """.trimIndent(),
            createdAt = SEED_TIME,
            updatedAt = SEED_TIME,
            version = 1,
        ),
        WorkflowTemplate(
            name = "uc4_content_publish",
            description = "Draft a post, publish it after approval, and collect its likes metric.",
            intentExamples = listOf(
                "draft and publish a post",
                "write a post and share it",
                "publish my weekly update",
            ),
            planJson = """
                {"title":"Draft, approve and publish a post",
                 "steps":[
                   {"id":"draft_post","kind":"reason","title":"Draft the post",
                    "prompt":"Write a calm, specific post of at most 120 words for the user's audience, with one concrete example and no unverified claims."},
                   {"id":"approve_post","kind":"human_approval","title":"Approve the post","artifactType":"post"},
                   {"id":"publish_post","kind":"browser_automation","title":"Publish the approved post",
                    "provider":"grok","artifactHint":"send: publish the approved post","requiresGate":true},
                   {"id":"track_likes","kind":"metric_collect","title":"Collect likes on the post",
                    "metricKey":"likes","metricUrl":"https://example.com/post"}
                 ]}
            """.trimIndent(),
            createdAt = SEED_TIME,
            updatedAt = SEED_TIME,
            version = 1,
        ),
        WorkflowTemplate(
            name = "uc5_generic_plan",
            description = "Run a generic multi-step plan: reason about the goal, gate the result, then save it as tasks.",
            intentExamples = listOf(
                "plan a multi step goal",
                "make a plan and track it",
                "break this goal into tasks",
            ),
            planJson = """
                {"title":"Plan a goal and save the resulting tasks",
                 "steps":[
                   {"id":"plan_goal","kind":"reason","title":"Plan the goal",
                    "prompt":"Break the user's goal into up to five ordered steps, each with a clear done condition."},
                   {"id":"approve_plan","kind":"human_approval","title":"Approve the plan","artifactType":"task"},
                   {"id":"save_plan","kind":"data_persist","title":"Save the plan as tasks","artifactType":"task"}
                 ]}
            """.trimIndent(),
            createdAt = SEED_TIME,
            updatedAt = SEED_TIME,
            version = 1,
        ),
    )
}
