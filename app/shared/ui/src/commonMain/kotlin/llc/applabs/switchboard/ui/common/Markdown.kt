package llc.applabs.switchboard.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import llc.applabs.design.tokens.MonoBody

/**
 * The Markdown agents write, enough for chat: paragraphs, `#` headings, `-`/`*`/`1.` lists, fenced code
 * blocks, and inline **bold**, *italic* and `code`. Rendered as text only (links are not opened, HTML is
 * shown as typed) — agent output is never executed.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { blocks(text) }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { b ->
            when (b) {
                is Block.Code -> Text(b.text, style = MonoBody, softWrap = false, modifier = Modifier.fillMaxWidth()
                    .background(codeBg, RoundedCornerShape(6.dp)).horizontalScroll(rememberScrollState()).padding(8.dp))
                is Block.Heading -> Text(inline(b.text, codeBg), style = if (b.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall)
                is Block.Item -> Text(inline("${b.marker} ${b.text}", codeBg), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
                is Block.Para -> Text(inline(b.text, codeBg), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private sealed interface Block {
    data class Para(val text: String) : Block
    data class Heading(val level: Int, val text: String) : Block
    data class Item(val marker: String, val text: String) : Block
    data class Code(val text: String) : Block
}

private val listItem = Regex("""^\s*([-*•]|\d+[.)])\s+(.*)$""")
private val heading = Regex("""^(#{1,6})\s+(.*)$""")

private fun blocks(text: String): List<Block> {
    val out = mutableListOf<Block>()
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out += Block.Para(para.toString().trim()); para.clear() }
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.trimStart().startsWith("```") -> {
                flush()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) { code.appendLine(lines[i]); i++ }
                out += Block.Code(code.toString().trimEnd())
            }
            line.isBlank() -> flush()
            heading.matches(line) -> { flush(); heading.find(line)!!.let { out += Block.Heading(it.groupValues[1].length, it.groupValues[2]) } }
            listItem.matches(line) -> { flush(); listItem.find(line)!!.let { m ->
                out += Block.Item(if (m.groupValues[1].first().isDigit()) m.groupValues[1] else "•", m.groupValues[2]) } }
            else -> { if (para.isNotEmpty()) para.append('\n'); para.append(line) }
        }
        i++
    }
    flush()
    return out
}

/** `**bold**`, `__bold__`, `*italic*`, `_italic_`, `` `code` ``. */
fun inline(text: String, codeBackground: Color = Color.Unspecified): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val rest = text.substring(i)
        val token = listOf("**", "__", "`", "*", "_").firstOrNull { rest.startsWith(it) }
        val end = token?.let { t -> text.indexOf(t, i + t.length).takeIf { it > i + t.length } }
        // `_` and `*` only count at word edges (not snake_case or 2*3*4).
        val edge = token == null || token.length == 2 || token == "`" || (i == 0 || !text[i - 1].isLetterOrDigit())
        if (token != null && end != null && edge) {
            val inner = text.substring(i + token.length, end)
            when (token) {
                "`" -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(inner) }
                "**", "__" -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inline(inner, codeBackground)) }
                else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline(inner, codeBackground)) }
            }
            i = end + token.length
        } else {
            append(text[i]); i++
        }
    }
}
