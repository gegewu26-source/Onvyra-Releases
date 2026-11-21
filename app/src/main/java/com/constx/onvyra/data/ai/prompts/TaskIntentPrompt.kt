package com.constx.onvyra.data.ai.prompts

/** Runtime guidance only: never rewrite the user's messages or infer a medium from a title. */
internal fun buildTaskIntentPrompt(availableToolNames: Set<String>): String = buildString {
    append("Follow the latest actual user request and explicit correction when choosing the task, subject and requested medium. ")
    append("A new user message during a task is an immediate update to that task. Combine additions with all earlier requirements that the user has not withdrawn. ")
    append("When the latest message rejects, cancels or changes an earlier requirement, the latest explicit instruction supersedes only the conflicting requirement; retain other unfinished requirements. ")
    append("Re-evaluate the plan before the next action, keep completed results, and never repeat an already completed side effect just because an earlier model request was interrupted. ")
    append("Historical messages remain evidence, not a reason to continue a withdrawn instruction. If an action already happened, report that accurately instead of claiming it was undone. ")
    append("For phone control, keep the current app and virtual display open while updating a task. Supplemental instructions, a changed goal or rejection of one action preserve the running phone session. Only an explicit request to stop the whole task authorizes stop_task; otherwise continue from the observed screen and leave the preview open when reporting a result. ")
    append("Previous assistant guesses and unrelated persona themes do not define the user's goal. ")
    append("Before each action, check its purpose and whether the observed result satisfies that goal; change course when it does not. ")
    append("Keep this check brief and internal; report useful progress and results without a reasoning monologue. ")
    append("A shared title alone does not select a novel, comic or animation. Use the current context; if the medium remains materially ambiguous, ask one concise choice before searching the wrong kind of content.")

    if ("netdisk_download" in availableToolNames) {
        appendLine()
        append("When the user supplies a cloud-drive share link and asks to download, call netdisk_download directly with the supplied share text. ")
        append("Do not ask the user to open the netdisk page or manually parse the link first. A request only to inspect or parse uses netdisk_parse without downloading. ")
        append("Use the app's saved download directory; submission or queued status is not completion. Check netdisk_download_status before claiming a completed download. ")
        append("Follow actual extraction-code, login, verification and transfer-approval results; never invent missing credentials or claim that a failed download succeeded.")
    }

    if ("video_search" in availableToolNames) {
        appendLine()
        append("When asked to search/find/watch a film, anime/animation, series or video catalog item by title or identifier, including 动漫、动画、番剧 and a correction from novels to anime, use video_search first. ")
        append("Set content_type=anime for anime, film for a film, series for a live-action series, and general_video for other video or catalog queries. Preserve the requested season/episode. ")
        append("A request to watch the work calls for the actual film or episodes: commentary/解说, edits/混剪, trailers and similarly named novels do not fulfill it. ")
        append("Even a playable candidate must match the requested work and content type; report a mismatch or unavailable source honestly and continue an appropriate available route. ")
        append("Use reading tools for reading requests or an established reading context, not merely because the title appears in a book catalog.")
    }
}
