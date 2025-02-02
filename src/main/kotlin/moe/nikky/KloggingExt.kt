package moe.nikky

import dev.kordex.core.extensions.Extension
import dev.kord.core.behavior.GuildBehavior
import dev.kord.core.entity.Guild
import dev.kord.core.entity.channel.GuildChannel
import dev.kord.core.event.Event
import dev.kord.core.event.interaction.InteractionCreateEvent
import dev.kordex.core.i18n.toKey
import io.klogging.context.logContext
import io.klogging.events.LogEvent
import io.klogging.logger
import io.klogging.rendering.RenderString
import io.klogging.rendering.colour5
import io.klogging.rendering.evalTemplate
import io.klogging.rendering.localString
import io.klogging.sending.SendString
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

val DOCKER_RENDERER: RenderString = RenderString { e ->
    val loggerOrFile = e.items["file"] ?: e.logger
    val message = "${e.level.colour5} $loggerOrFile : ${e.evalTemplate()}"
    val cleanedItems = e.items - "file"
    val maybeItems = if (cleanedItems.isNotEmpty()) " : $cleanedItems" else ""
    val maybeStackTrace = if (e.stackTrace != null) "\n${e.stackTrace}" else ""
    message + maybeItems + maybeStackTrace
}

val CUSTOM_RENDERER: RenderString = RenderString { e ->
    val loggerOrFile = e.items["file"]?.let { ".($it)" } ?: e.logger
    val time = e.timestamp.localString.substring(0..22)
    val message = "$time ${e.level} $loggerOrFile : ${e.evalTemplate()}"
    val cleanedItems = e.items - "file"
    val maybeItems = if (cleanedItems.isNotEmpty()) " : $cleanedItems" else ""
    val maybeStackTrace = if (e.stackTrace != null) "\n${e.stackTrace}" else ""
    message + maybeItems + maybeStackTrace
}
val CUSTOM_RENDERER_ANSI: RenderString = RenderString { e ->
    val loggerOrFile = e.items["file"]?.let { ".($it)" } ?: e.logger
    val time = e.timestamp.localString.substring(0..22)
    val message = "$time ${e.level.colour5} $loggerOrFile : ${e.evalTemplate()}"
    val cleanedItems = e.items - "file"
    val maybeItems = if (cleanedItems.isNotEmpty()) " : $cleanedItems" else ""
    val maybeStackTrace = if (e.stackTrace != null) "\n${e.stackTrace}" else ""
    message + maybeItems + maybeStackTrace
}

//fun logFile(file: File, append: Boolean = false): SendString {
//    file.parentFile.mkdirs()
//    if (!file.exists()) file.createNewFile()
//    val sink = file.sink(append = append).buffer()
//
//    return { line ->
//        sink.writeUtf8(line + "\n")
//    }
//}

@OptIn(ExperimentalCoroutinesApi::class)
val logScope = CoroutineScope(
    Dispatchers.IO.limitedParallelism(10) + CoroutineName("log")
)

class LogFile(
    file: File,
    append: Boolean = false
): SendString {
    private val channel = Channel<String>()
    init {
        runBlocking {
            file.parentFile.mkdirs()
            if(!append && file.exists()) {
                file.delete()
                withContext(Dispatchers.IO) {
                    file.createNewFile()
                }
            } else if(!file.exists()) {
                withContext(Dispatchers.IO) {
                    file.createNewFile()
                }
            }
            logScope.launch {
                file.writeChannel().use {
                    for (line in channel) {
                        writeStringUtf8(line + "\n")
                    }
                }
            }
        }
    }

    override fun invoke(eventString: String) {
        logScope.launch {
            channel.send(eventString)
        }
    }
}

suspend fun logFile(file: File, append: Boolean = false): SendString {
    file.parentFile.mkdirs()
    if(!append && file.exists()) {
        file.delete()
        withContext(Dispatchers.IO) {
            file.createNewFile()
        }
    } else if(!file.exists()) {
        withContext(Dispatchers.IO) {
            file.createNewFile()
        }
    }

    val channel = Channel<String>()
    logScope.launch {
        file.writeChannel().use {
             for (line in channel) {
                 writeStringUtf8(line + "\n")
             }
        }
    }

    return object : SendString {
        override fun invoke(eventString: String) {
            logScope.launch {
                channel.send(eventString)
            }
        }
    }
}

private val logger = logger("moe.nikky.KloggingExt")
suspend fun <E : Event, T> Extension.withLogContext(
    event: E,
    guildBehavior: GuildBehavior?,
    block: suspend CoroutineScope.(Guild) -> T,
): T {
    val guild = guildBehavior?.asGuild() ?: relayError("cannot load guild".toKey())
    val items = mutableListOf<Pair<String, String?>>()
    if (event is InteractionCreateEvent) {
        items += "channel" to (event.interaction.channel.asChannel() as? GuildChannel)?.name
    }
    val eventName = event::class.simpleName
    items += listOf(
        "guild" to "'${guild.name}'",
        "event" to eventName,
        "extension" to name,
    )
    return withContext(
        logContext(*items.toTypedArray())
    ) {
        logger.infoF { "triggered event $eventName" }
        block(guild)
    }
}

suspend fun <E : Event, T> Extension.withLogContextOptionalGuild(
    event: E,
    guildBehavior: GuildBehavior?,
    block: suspend CoroutineScope.(Guild?) -> T,
): T {
    val guild = guildBehavior?.asGuild()
    val items = mutableListOf<Pair<String, String?>>()
    if (event is InteractionCreateEvent) {
        items += "channel" to (event.interaction.channel.asChannel() as? GuildChannel)?.name
    }
    val eventName = event::class.simpleName
    if(guild != null) {
        items += "guild" to "'${guild.name}'"
    }
    items += listOf(
        "event" to eventName,
        "extension" to name,
    )
    return withContext(
        logContext(*items.toTypedArray())
    ) {
        logger.infoF { "triggered event $eventName" }
        block(guild)
    }
}
