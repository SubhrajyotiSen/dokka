package org.jetbrains.dokka.templates

import org.jetbrains.dokka.DokkaConfiguration
import org.jetbrains.dokka.base.templating.Command
import org.jetbrains.dokka.base.templating.parseJson
import org.jetbrains.dokka.plugability.DokkaContext
import org.jetbrains.dokka.plugability.plugin
import org.jetbrains.dokka.plugability.query
import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.File
import java.nio.file.Files

const val TEMPLATE_COMMAND_SEPARATOR = ":"
const val TEMPLATE_COMMAND_BEGIN_BORDER  = "[+]cmd"
const val TEMPLATE_COMMAND_END_BORDER  = "[-]cmd"

class DirectiveBasedHtmlTemplateProcessingStrategy(private val context: DokkaContext) : TemplateProcessingStrategy {

    private val directiveBasedCommandHandlers =
        context.plugin<TemplatingPlugin>().query { directiveBasedCommandHandlers }

    override fun process(input: File, output: File, moduleContext: DokkaConfiguration.DokkaModuleDescription?): Boolean =
        if (input.isFile && input.extension == "html") {
            val document = Jsoup.parse(input, "UTF-8")
            document.outputSettings().indentAmount(0).prettyPrint(false)

            document.select("dokka-template-command").forEach {
                handleCommandAsTag(it, parseJson(it.attr("data")), input, output)
            }
            extractCommandsFromComments(document) { command, body  ->
                val bodyTrimed =
                    body.dropWhile { node -> (node is TextNode && node.isBlank).also { if (it) node.remove() } }
                        .dropLastWhile { node -> (node is TextNode && node.isBlank).also { if (it) node.remove() } }
                handleCommandAsComment(command, bodyTrimed, input, output)
            }

            Files.write(output.toPath(), listOf(document.outerHtml()))
            true
        } else false

    fun handleCommandAsTag(element: Element, command: Command, input: File, output: File) {
        traverseHandlers(command) { handleCommandAsTag(command, element, input, output) }
    }

    fun handleCommandAsComment(command: Command, body: List<Node>, input: File, output: File) {
        traverseHandlers(command) { handleCommandAsComment(command, body, input, output) }
    }

    private fun traverseHandlers(command: Command, action: CommandHandler.() -> Unit)  {
        val handlers = directiveBasedCommandHandlers.filter { it.canHandle(command) }
        if (handlers.isEmpty())
            context.logger.warn("Unknown templating command $command")
        else
            handlers.forEach(action)
    }

    private fun extractCommandsFromComments(
        node: Node,
        startFrom: Int = 0,
        handler: (command: Command, body: List<Node>) -> Unit
    ) {
        val nodes: MutableList<Node> = mutableListOf()
        var lastStartBorder: Comment? = null
        var firstStartBorder: Comment? = null
        for (index in startFrom until node.childNodeSize()) {
            when (val currentChild = node.childNode(index)) {
                is Comment -> if (currentChild.data.startsWith(TEMPLATE_COMMAND_BEGIN_BORDER)) {
                    lastStartBorder = currentChild
                    firstStartBorder = firstStartBorder ?: currentChild
                    nodes.clear()
                } else if (lastStartBorder != null && currentChild.data.startsWith(TEMPLATE_COMMAND_END_BORDER)) {
                    lastStartBorder.remove()
                    val cmd = lastStartBorder.data
                        .removePrefix("$TEMPLATE_COMMAND_BEGIN_BORDER$TEMPLATE_COMMAND_SEPARATOR")
                        .let { parseJson<Command>(it) }

                    handler(cmd, nodes)
                    currentChild.remove()
                    extractCommandsFromComments(node, firstStartBorder?.siblingIndex() ?: 0, handler)
                    return
                } else {
                    if (lastStartBorder != null) nodes.add(currentChild)
                }
                else -> {
                    extractCommandsFromComments(currentChild, handler = handler)
                    if (lastStartBorder != null) nodes.add(currentChild)
                }
            }
        }
    }

    override fun finish(output: File) {
        directiveBasedCommandHandlers.forEach { it.finish(output) }
    }
}