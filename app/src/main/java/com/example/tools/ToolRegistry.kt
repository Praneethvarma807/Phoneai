package com.example.tools

import com.example.domain.model.ActionCategory
import com.example.domain.model.ExecutionStatus
import com.example.domain.model.ToolResult
import com.example.domain.model.ToolSchemaInfo

interface CommanderTool {
    val schema: ToolSchemaInfo
    val category: ActionCategory

    fun validate(arguments: Map<String, String>): String? = null
    suspend fun execute(arguments: Map<String, String>): ToolResult
}

class ToolRegistry {
    private val tools = LinkedHashMap<String, CommanderTool>()

    fun register(tool: CommanderTool) {
        tools[tool.schema.name] = tool
    }

    fun registerAll(list: List<CommanderTool>) {
        list.forEach { register(it) }
    }

    fun get(name: String): CommanderTool? = tools[name]

    fun registeredNames(): Set<String> = tools.keys.toSet()

    fun allSchemas(): List<ToolSchemaInfo> = tools.values.map { it.schema }

    fun allTools(): List<CommanderTool> = tools.values.toList()

    suspend fun executeTool(name: String, arguments: Map<String, String>): ToolResult {
        val tool = tools[name]
            ?: return ToolResult(
                toolName = name,
                status = ExecutionStatus.UNSUPPORTED,
                message = "Tool '$name' is not registered in the Tool Registry.",
                verificationNote = "Registry lookup failed"
            )

        val validationError = tool.validate(arguments)
        if (validationError != null) {
            return ToolResult(
                toolName = name,
                status = ExecutionStatus.FAILED,
                message = validationError,
                verificationNote = "Input schema validation failed",
                category = tool.category
            )
        }

        return try {
            tool.execute(arguments)
        } catch (e: SecurityException) {
            ToolResult(
                toolName = name,
                status = ExecutionStatus.PERMISSION_REQUIRED,
                message = "Missing permission for $name: ${e.message}",
                verificationNote = "SecurityException intercepted",
                missingPermissions = tool.schema.requiredPermissions,
                category = tool.category
            )
        } catch (e: Exception) {
            ToolResult(
                toolName = name,
                status = ExecutionStatus.FAILED,
                message = e.message ?: "Unexpected error executing $name",
                verificationNote = "Exception handled safely",
                category = tool.category
            )
        }
    }
}
