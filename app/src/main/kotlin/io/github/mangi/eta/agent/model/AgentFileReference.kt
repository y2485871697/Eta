package io.github.mangi.eta.agent.model

enum class AgentFileReferenceKind {
    File,
    Directory,
}

data class AgentFileReference(
    val displayName: String,
    val absolutePath: String,
    val kind: AgentFileReferenceKind,
)

internal data class AgentFileReferencePrompt(
    val request: String,
    val references: List<AgentFileReference>,
)

internal object AgentFileReferencePolicy {
    fun canSend(
        references: List<AgentFileReference>,
        terminalToolsEnabled: Boolean,
    ): Boolean = references.isEmpty() || terminalToolsEnabled

    fun titleSource(
        request: String,
        references: List<AgentFileReference>,
    ): String = request.ifBlank { references.firstOrNull()?.displayName.orEmpty() }
}

/** 生成并解析 Eta 自己写入用户消息的本地路径上下文。 */
internal object AgentFileReferencePromptCodec {
    private const val FILES_HEADER = "# Files mentioned by the user:"
    private const val REQUEST_HEADER = "## My request:"
    private const val ENTRY_PREFIX = "## "

    fun format(
        request: String,
        references: List<AgentFileReference>,
    ): String {
        val uniqueReferences = references.distinctBy { it.absolutePath }
        if (uniqueReferences.isEmpty()) return request

        return buildString {
            appendLine(FILES_HEADER)
            appendLine()
            uniqueReferences.forEachIndexed { index, reference ->
                if (index > 0) appendLine()
                append(ENTRY_PREFIX)
                append(reference.displayLabel)
                append(": ")
                appendLine(reference.absolutePath)
            }
            appendLine()
            append(REQUEST_HEADER)
            if (request.isNotEmpty()) {
                appendLine()
                append(request)
            }
        }
    }

    fun parse(content: String): AgentFileReferencePrompt {
        val prefix = "$FILES_HEADER\n\n"
        if (!content.startsWith(prefix)) {
            return AgentFileReferencePrompt(request = content, references = emptyList())
        }
        val requestDelimiter = "\n\n$REQUEST_HEADER"
        val requestHeaderIndex = content.indexOf(requestDelimiter, startIndex = prefix.length)
        if (requestHeaderIndex < 0) {
            return AgentFileReferencePrompt(request = content, references = emptyList())
        }

        val entriesText = content.substring(prefix.length, requestHeaderIndex)
        val references = entriesText
            .split("\n\n")
            .mapNotNull(::parseReference)
        if (references.isEmpty() || references.size != entriesText.split("\n\n").size) {
            return AgentFileReferencePrompt(request = content, references = emptyList())
        }

        val requestStart = requestHeaderIndex + requestDelimiter.length
        val request = when {
            requestStart == content.length -> ""
            content.getOrNull(requestStart) == '\n' -> content.substring(requestStart + 1)
            else -> return AgentFileReferencePrompt(request = content, references = emptyList())
        }
        return AgentFileReferencePrompt(
            request = request,
            references = references.distinctBy { it.absolutePath },
        )
    }

    private fun parseReference(line: String): AgentFileReference? {
        if (!line.startsWith(ENTRY_PREFIX) || line.contains('\n')) return null
        val body = line.removePrefix(ENTRY_PREFIX)
        val delimiterIndex = body.lastIndexOf(": /")
        if (delimiterIndex <= 0) return null
        val rawLabel = body.substring(0, delimiterIndex)
        val absolutePath = body.substring(delimiterIndex + 2)
        val isDirectory = rawLabel.endsWith('/')
        val displayName = rawLabel.removeSuffix("/")
        if (
            displayName.isBlank() ||
            displayName.hasUnsupportedControlCharacter() ||
            !absolutePath.startsWith('/') ||
            absolutePath.hasUnsupportedControlCharacter()
        ) {
            return null
        }
        return AgentFileReference(
            displayName = displayName,
            absolutePath = absolutePath,
            kind = if (isDirectory) AgentFileReferenceKind.Directory else AgentFileReferenceKind.File,
        )
    }

    private val AgentFileReference.displayLabel: String
        get() = displayName + if (kind == AgentFileReferenceKind.Directory) "/" else ""
}

internal fun String.hasUnsupportedControlCharacter(): Boolean =
    any { it == '\u0000' || it == '\r' || it == '\n' || it.isISOControl() }
