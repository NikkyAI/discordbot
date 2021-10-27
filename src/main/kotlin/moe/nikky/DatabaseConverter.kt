package moe.nikky

import com.kotlindiscord.kord.extensions.utils.envOrNull
import dev.kord.common.entity.Snowflake
import io.klogging.Level
import io.klogging.config.loggingConfiguration
import io.klogging.logger
import io.klogging.sending.STDOUT
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import moe.nikky.json.VersionMigrator
import moe.nikky.json.VersionedSerializer
import kotlinx.serialization.SerializationException
import java.io.File

private val configFolder = File(envOrNull("CONFIG_DIR") ?: "data")
private val configFile = configFolder.resolve("config.json")

private val logger = logger("moe.nikky.DatabaseConverter")

@OptIn(ExperimentalSerializationApi::class)
private val json = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
}

private fun <K, V> Map<K, V>.replaceKey(oldKey: K, newKey: K, transform: (V) -> V = { it }): Map<K, V> =
    this - oldKey + (newKey to transform(getValue(oldKey)))

private fun <K, V> Map<K, V>.replaceValue(key: K, transform: (V) -> V): Map<K, V> =
    this + (key to transform(getValue(key)))

private fun Map<String, JsonElement>.toJsonObject() = JsonObject(this)

private val versionedSerializer = VersionedSerializer(
    MapSerializer(
        String.serializer(),
        GuildConfiguration.serializer()
    ),
    currentVersion = 2,
    migrations = mapOf(
        0..1 to VersionMigrator(
            json,
            JsonObject.serializer(),
            new = JsonObject.serializer()
        ) { obj: JsonObject ->
            obj.mapValues { (_, guildObj) ->
                guildObj.jsonObject.replaceValue("twitchNotifications") { twitchNotificationsObj ->
                    twitchNotificationsObj.jsonObject.mapValues { (_, entry) ->
                        entry.jsonObject.replaceKey("oldMessage", "message").toJsonObject()
                    }.toJsonObject()
                }.toJsonObject()
            }.toJsonObject().also {
                val objectString = json.encodeToString(JsonObject.serializer(), it)
                runBlocking {
                    logger.infoF { "migration output: ${objectString}" }
                }
            }
        },
        1..2 to VersionMigrator(
            json, JsonObject.serializer(), JsonObject.serializer()
        ) { obj: JsonObject ->
            obj.mapValues { (_, guildObj) ->
                guildObj.jsonObject.replaceKey("editableRoles", "roleChooser") {
                    JsonObject(emptyMap())
                }.toJsonObject()
            }.toJsonObject()
        }
    )
)

fun main(args: Array<String>) = runBlocking {

    loggingConfiguration {
        sink("stdout", CUSTOM_RENDERER_ANSI, STDOUT)
        logging {
            fromMinLevel(Level.DEBUG) {
                toSink("stdout")
            }
        }
    }


    val config = ConfigurationService()

    val database = config.database

    val guildConfigQueries = database.guildConfigQueries
    val twitchConfigQueries = database.twitchConfigQueries

    // load config
    val configs = loadJson()

    configs.forEach { (id, guildConfig) ->
        val guildId = Snowflake(id)
        guildConfigQueries.transaction {
            guildConfigQueries.upsert(
                guildId = guildId,
                name = guildConfig.name
            )

            if(guildConfig.adminRole != null) {
                guildConfigQueries.updateAdminRole(
                    adminRole = guildConfig.adminRole,
                    guildId = guildId
                )
            }

            guildConfig.twitchNotifications.forEach { (key, twitchConfig) ->
                twitchConfigQueries.upsert(
                    guildId = guildId,
                    channel = twitchConfig.channel,
                    twitchUserName = twitchConfig.twitchUserName,
                    role = twitchConfig.role,
                    message = null, //twitchConfig.message
                )
                if (twitchConfig.message != null) {
                    twitchConfigQueries.updateMessage(
                        guildId = guildId,
                        channel = twitchConfig.channel,
                        twitchUserName = twitchConfig.twitchUserName,
                        message = twitchConfig.message
                    )
                }
            }

            guildConfig.roleChooser.forEach { (section, roleChooserConfig) ->
                database.roleChooserQueries.upsert(
                    guildId = guildId,
                    section = section,
                    description = null,
                    channel = roleChooserConfig.channel,
                    message = roleChooserConfig.message
                )
                val row = database.roleChooserQueries.find(
                    guildId = guildId,
                    section = section,
                    channel = roleChooserConfig.channel
                ).executeAsOneOrNull() ?: return@forEach

                roleChooserConfig.roleMapping.forEach { (reaction, role) ->
                    database.roleMappingQueries.upsert(
                        roleChooserId = row.roleChooserId,
                        reaction = reaction,
                        role = role
                    )
                }
            }

        }
    }

    guildConfigQueries.getAll().executeAsList().forEach { guildConfig ->

    }


}

private suspend fun loadJson(): Map<String, GuildConfiguration> {
    logger.infoF { "loading from ${configFile.absolutePath}" }
    configFile.absoluteFile.parentFile.mkdirs()
    if (configFile.exists()) {
        val newConfigurations = try {
            json.decodeFromString(
                versionedSerializer,
                configFile.readText()
            ).toMutableMap()
        } catch (e: SerializationException) {
            e.printStackTrace()
            throw e
        }
        return newConfigurations.toMap()
    } else {
        error("file $configFile does not exist")
    }
}